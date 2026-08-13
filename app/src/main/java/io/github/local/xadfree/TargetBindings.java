package io.github.local.xadfree;

import android.view.View;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Exact, version-specific reflective bindings for X 12.3.1-release.0. */
final class TargetBindings {
    static final String TIMELINE_ROW_CLASS = "com.twitter.ui.view.GroupedRowView";

    final Method tweetBinder;
    final Method holderView;
    final Field timelineTweet;
    final Field promotedMetadata;

    final Method adBadgeUpdater;
    final Field updaterCase;
    final Field updaterPayload;
    final Field badgeView;

    final Method urtEmit;

    TargetBindings(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> binderClass = load(loader, "com.twitter.timeline.itembinder.b1");
        Class<?> binderHolderClass = load(loader, "com.twitter.util.ui.viewholder.b");
        Class<?> concreteHolderClass = load(loader, "com.twitter.timeline.tweet.viewholder.b");
        Class<?> timelineItemClass = load(loader, "com.twitter.model.timeline.o2");
        Class<?> scopeClass = load(loader, "com.twitter.util.di.scope.e");
        Class<?> tweetClass = load(loader, "com.twitter.model.core.e");

        tweetBinder = declaredMethod(
                binderClass, "k", binderHolderClass, Object.class, scopeClass);
        holderView = publicMethod(concreteHolderClass, "N");
        timelineTweet = declaredField(timelineItemClass, "k");
        promotedMetadata = declaredField(tweetClass, "b");

        Class<?> updaterClass = load(loader, "com.twitter.app.settings.h1");
        Class<?> badgeDelegateClass = load(loader, "com.twitter.tweetview.core.ui.badge.b");
        adBadgeUpdater = declaredMethod(updaterClass, "invoke", Object.class);
        updaterCase = declaredField(updaterClass, "a");
        updaterPayload = declaredField(updaterClass, "b");
        badgeView = declaredField(badgeDelegateClass, "a");

        Class<?> urtCollectorClass = load(loader, "com.x.repositories.urt.j$a");
        Class<?> continuationClass = load(loader, "kotlin.coroutines.Continuation");
        urtEmit = declaredMethod(
                urtCollectorClass, "emit", Object.class, continuationClass);
    }

    View boundRow(Object holder) throws ReflectiveOperationException {
        Object value = holderView.invoke(holder);
        return value instanceof View ? (View) value : null;
    }

    boolean isLegacyPromotedItem(Object timelineItem) throws IllegalAccessException {
        Object tweet = timelineTweet.get(timelineItem);
        return tweet != null && promotedMetadata.get(tweet) != null;
    }

    View badgeFromUpdater(Object updater) throws IllegalAccessException {
        if (updaterCase.getInt(updater) != 2) {
            return null;
        }
        Object delegate = updaterPayload.get(updater);
        if (delegate == null || !badgeView.getDeclaringClass().isInstance(delegate)) {
            return null;
        }
        Object value = badgeView.get(delegate);
        return value instanceof View ? (View) value : null;
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Method declaredMethod(
            Class<?> owner,
            String name,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static Method publicMethod(
            Class<?> owner,
            String name,
            Class<?>... parameterTypes) throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameterTypes);
        method.setAccessible(true);
        return method;
    }

    private static Field declaredField(Class<?> owner, String name) throws NoSuchFieldException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
