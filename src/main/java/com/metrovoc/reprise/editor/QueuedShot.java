package com.metrovoc.reprise.editor;

import java.util.UUID;

/** The editor state and viewpoint captured when a shot is queued. */
public record QueuedShot(String name, ReplayProject project, CameraPose camera, UUID spectating) {}
