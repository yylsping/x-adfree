package io.github.yylsping.xadfree;

import java.util.ArrayList;
import java.util.List;

/** Filters promoted URT entries and allocates a replacement only after the first match. */
final class UrtListFilter {
    private final AdDetector detector;

    UrtListFilter(AdDetector detector) {
        this.detector = detector;
    }

    List<?> filter(List<?> incoming) {
        ArrayList<Object> filtered = null;
        int size = incoming.size();
        for (int index = 0; index < size; index++) {
            Object entry = incoming.get(index);
            if (detector.isAdvertisement(entry)) {
                if (filtered == null) {
                    filtered = new ArrayList<>(Math.max(0, size - 1));
                    for (int prefix = 0; prefix < index; prefix++) {
                        filtered.add(incoming.get(prefix));
                    }
                }
            } else if (filtered != null) {
                filtered.add(entry);
            }
        }
        return filtered == null ? incoming : filtered;
    }
}
