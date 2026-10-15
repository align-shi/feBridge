package com.febridge.knowledge;

import java.util.ArrayList;
import java.util.List;

public final class TextChunks {

    private TextChunks() {}

    public static List<String> split(String text) {
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n").strip();
        int[] points = normalized.codePoints().toArray();
        var chunks = new ArrayList<String>();
        int start = 0;
        while (start < points.length) {
            int end = Math.min(points.length, start + 300);
            if (end < points.length) {
                for (int i = end - 1; i >= start + 180; i--) {
                    if (points[i] == '\n' || points[i] == '。' || points[i] == '.') {
                        end = i + 1;
                        break;
                    }
                }
            }
            String chunk = new String(points, start, end - start).strip();
            if (!chunk.isBlank()) {
                chunks.add(chunk);
            }
            if (end == points.length) {
                break;
            }
            start = end - 40;
        }
        return List.copyOf(chunks);
    }
}
