package io.github.yylsping.xadfree;

import android.view.View;
import android.view.ViewGroup;

import java.util.Map;
import java.util.WeakHashMap;

/** Stores only rows changed by the module, using weak keys so recycled views are not retained. */
final class CollapsedViewRegistry {
    private final Map<View, OriginalState> collapsed = new WeakHashMap<>();

    boolean collapse(View row) {
        OriginalState state;
        synchronized (collapsed) {
            state = collapsed.get(row);
            if (state != null) {
                return false;
            }
            ViewGroup.LayoutParams params = row.getLayoutParams();
            state = new OriginalState(
                    params == null ? null : params.height,
                    row.getVisibility());
            collapsed.put(row, state);
        }

        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params != null && params.height != 0) {
            params.height = 0;
            row.setLayoutParams(params);
        }
        if (row.getVisibility() != View.GONE) {
            row.setVisibility(View.GONE);
        }
        return true;
    }

    boolean restore(View row) {
        OriginalState state;
        synchronized (collapsed) {
            state = collapsed.remove(row);
        }
        if (state == null) {
            return false;
        }

        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params != null
                && state.height != null
                && params.height != state.height) {
            params.height = state.height;
            row.setLayoutParams(params);
        }
        if (row.getVisibility() != state.visibility) {
            row.setVisibility(state.visibility);
        }
        return true;
    }

    private static final class OriginalState {
        final Integer height;
        final int visibility;

        OriginalState(Integer height, int visibility) {
            this.height = height;
            this.visibility = visibility;
        }
    }
}
