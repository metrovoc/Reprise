package com.metrovoc.reprise.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Reversible changes to persisted project fields. Timeline edits store only the changed run of keys. */
final class ProjectHistory {
    private record Edit(String field, int index, JsonElement before, JsonElement after) {
        void apply(JsonObject state, boolean forward) {
            JsonElement value = forward ? after : before;
            if (index < 0) {
                state.add(field, value);
                return;
            }
            var list = state.getAsJsonArray(field).asList();
            int removed = (forward ? before : after).getAsJsonArray().size();
            list.subList(index, index + removed).clear();
            list.addAll(index, value.getAsJsonArray().asList());
        }
    }

    private final List<List<Edit>> entries = new ArrayList<>();
    private int cursor;

    void record(JsonObject before, JsonObject after) {
        var edits = new ArrayList<Edit>();
        for (var field : after.entrySet()) {
            String key = field.getKey();
            JsonElement old = before.get(key), value = field.getValue();
            if (value.equals(old)) continue;
            if (old != null && old.isJsonArray() && value.isJsonArray()) {
                var a = old.getAsJsonArray();
                var b = value.getAsJsonArray();
                int prefix = 0, aEnd = a.size(), bEnd = b.size();
                while (prefix < aEnd && prefix < bEnd && a.get(prefix).equals(b.get(prefix))) prefix++;
                while (aEnd > prefix && bEnd > prefix && a.get(aEnd - 1).equals(b.get(bEnd - 1))) {
                    aEnd--;
                    bEnd--;
                }
                edits.add(new Edit(key, prefix, slice(a, prefix, aEnd), slice(b, prefix, bEnd)));
            } else {
                edits.add(new Edit(key, -1, old, value));
            }
        }
        if (edits.isEmpty()) return;
        entries.subList(cursor, entries.size()).clear();
        entries.add(edits);
        if (entries.size() > 200) entries.removeFirst();
        cursor = entries.size();
    }

    private static JsonArray slice(JsonArray source, int start, int end) {
        var result = new JsonArray(end - start);
        result.asList().addAll(source.asList().subList(start, end));
        return result;
    }

    boolean available(boolean forward) {
        return forward ? cursor < entries.size() : cursor > 0;
    }

    boolean apply(JsonObject state, boolean forward) {
        if (!available(forward)) return false;
        for (Edit edit : entries.get(forward ? cursor : cursor - 1)) edit.apply(state, forward);
        cursor += forward ? 1 : -1;
        return true;
    }
}
