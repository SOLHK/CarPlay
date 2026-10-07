package com.shilapi.xcertplay;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

/** Refresh only a visible, focused settings window. No background timer or per-frame GPS work. */
public final class LocationPanel implements Runnable, View.OnAttachStateChangeListener,
        ViewTreeObserver.OnWindowFocusChangeListener {
    private final TextView view;
    private final Rect visibleBounds = new Rect();
    private boolean attached;
    private String displayed;
    private ViewTreeObserver observer;

    private LocationPanel(TextView view) { this.view = view; }

    public static void bind(TextView view) {
        LocationPanel panel = new LocationPanel(view);
        panel.updateText();
        view.addOnAttachStateChangeListener(panel);
        if (view.isAttachedToWindow()) panel.onViewAttachedToWindow(view);
    }

    @Override public void onViewAttachedToWindow(View ignored) {
        attached = true;
        observer = view.getViewTreeObserver();
        observer.addOnWindowFocusChangeListener(this);
        schedule();
    }

    @Override public void onViewDetachedFromWindow(View ignored) {
        attached = false;
        view.removeCallbacks(this);
        if (observer != null && observer.isAlive()) observer.removeOnWindowFocusChangeListener(this);
        observer = null;
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) { schedule(); }

    private void schedule() {
        view.removeCallbacks(this);
        if (attached && view.hasWindowFocus() && view.getWindowVisibility() == View.VISIBLE) run();
    }

    private void updateText() {
        String next = GnssTelemetry.describe();
        if (!next.equals(displayed)) {
            displayed = next;
            view.setText(next);
        }
    }

    @Override public void run() {
        if (!attached || !view.hasWindowFocus() || view.getWindowVisibility() != View.VISIBLE) return;
        if (view.isShown() && view.getLocalVisibleRect(visibleBounds)) updateText();
        view.postDelayed(this, 1000);
    }
}
