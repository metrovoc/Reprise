package com.metrovoc.reprise.format;

import com.metrovoc.reprise.ReprisePaths;
import com.metrovoc.reprise.util.Varints;
import io.netty.buffer.ByteBuf;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.minecraft.network.VarInt;

/** Loads the optional release's ReplayStudio classes without starting its Fabric mod. */
final class LegacyMcpr {
    private static final String JAR = "replaymod-26.1-2.6.28.jar";
    private static final String STUDIO = "com.replaymod.replaystudio.";
    private static final String VIA = STUDIO + "lib.viaversion.api.";

    interface Reader {
        void read(DataInputStream input, long size) throws IOException;
    }

    static void read(ZipFile zip, ZipEntry packets, Path folder, Reader reader) throws IOException {
        Path jar = ReprisePaths.root().resolve("converters").resolve(JAR);
        if (!Files.isRegularFile(jar)) throw new IOException("For Minecraft 1.21 recordings, place " + JAR + " in reprise/converters.");
        var registries = new LegacyRegistries();
        try (var input = new DataInputStream(new BufferedInputStream(zip.getInputStream(packets)))) {
            registries.scan(input, packets.getSize());
        }
        Path translated = Files.createTempFile(folder, ".translated-", ".part");
        try {
            try (var input = new DataInputStream(new BufferedInputStream(zip.getInputStream(packets)));
                 var expanded = registries.expand(input)) {
                translate(jar, expanded, translated);
            }
            try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(translated)))) {
                reader.read(input, Files.size(translated));
            }
        } finally {
            Files.deleteIfExists(translated);
        }
    }

    private static void translate(Path jar, InputStream input, Path destination) throws IOException {
        try (var loader = new URLClassLoader(new URL[]{jar.toUri().toURL()}, LegacyMcpr.class.getClassLoader());
             var out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(destination)))) {
            // ReplayStudio constructs packet registries from Via's remapping tables. Wait for their async loader.
            loader.loadClass(STUDIO + "viaversion.CustomViaManager").getMethod("initialize").invoke(null);
            Object manager = loader.loadClass(VIA + "Via").getMethod("getManager").invoke(null);
            Object protocols = loader.loadClass(VIA + "ViaManager").getMethod("getProtocolManager").invoke(manager);
            var complete = loader.loadClass(VIA + "protocol.ProtocolManager").getMethod("checkForMappingCompletion");
            while (!(boolean) complete.invoke(protocols)) Thread.sleep(10);

            var versionClass = loader.loadClass(VIA + "protocol.version.ProtocolVersion");
            var stateClass = loader.loadClass(VIA + "protocol.packet.State");
            var registryClass = loader.loadClass(STUDIO + "protocol.PacketTypeRegistry");
            var registry = registryClass.getMethod("get", versionClass, stateClass).invoke(null,
                versionClass.getMethod("getProtocol", int.class).invoke(null, 775), stateClass.getField("LOGIN").get(null));
            var streamClass = loader.loadClass(STUDIO + "io.ReplayInputStream");
            var next = streamClass.getMethod("readPacket");
            var dataClass = loader.loadClass(STUDIO + "PacketData");
            var time = dataClass.getMethod("getTime");
            var packet = dataClass.getMethod("getPacket");
            var release = dataClass.getMethod("release");
            var packetClass = loader.loadClass(STUDIO + "protocol.Packet");
            var type = packetClass.getMethod("getType");
            var id = packetClass.getMethod("getId");
            var buffer = packetClass.getMethod("getBuf");
            try (var stream = (InputStream) streamClass.getConstructor(registryClass, InputStream.class, int.class, int.class)
                     .newInstance(registry, input, 14, 767)) {
                for (Object data; (data = next.invoke(stream)) != null;) {
                    try {
                        Object p = packet.invoke(data);
                        int packetId = (int) id.invoke(p);
                        ByteBuf body = ((ByteBuf) buffer.invoke(p)).duplicate();
                        String packetType = ((Enum<?>) type.invoke(p)).name();
                        out.writeInt(Math.toIntExact((long) time.invoke(data)));
                        out.writeInt(body.readableBytes() + VarInt.getByteSize(packetId));
                        Varints.write(out, packetId);
                        if (packetType.equals("JoinGame")) {
                            // ReplayStudio negates the recorder ID for its spectator. Reprise tracks the recorded entity.
                            out.writeInt(~body.readInt());
                        }
                        body.readBytes(out, body.readableBytes());
                    } finally {
                        release.invoke(data);
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Replay conversion interrupted", e);
        } catch (ReflectiveOperationException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException invocation ? invocation.getCause() : e;
            throw new IOException("ReplayStudio conversion failed: " + cause, cause);
        }
    }

    private LegacyMcpr() {}
}
