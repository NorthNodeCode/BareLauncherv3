package com.bare.launcher;

import android.content.ComponentName;
import android.content.pm.ResolveInfo;

import java.util.Map;

/**
 * Record describing one launchable app on the home shelf.
 *
 * <p>The identity fields ({@link #packageName}, {@link #label},
 * {@link #component}) are immutable; {@link #ri} and {@link #displayLabel}
 * are mutable per-instance memo caches populated after construction (see
 * their individual javadocs for the threading contract).
 *
 * <p>Hot-path access pattern:
 * <ul>
 *   <li>{@link #packageName} — keys for icon caches, the keymap
 *       SparseArray, the hidden-apps ArraySet, and the app-list
 *       persisted-order map.</li>
 *   <li>{@link #label} — drawn directly under each cell's icon. This is
 *       the label the user SEES: the app's own name, or their rename
 *       (v2.3.0). {@link #systemLabel} keeps the app's own name.</li>
 *   <li>{@link #component} — fast-path launch via
 *       {@code Intent.setComponent} (skips PackageManager resolution
 *       binder hops; saves 50-200 ms per launch on slow ROMs).</li>
 *   <li>{@link #ri} — used by {@code preWarmIcon} / {@code loadIconAsync}
 *       to call {@link ResolveInfo#loadIcon}. May be {@code null} for
 *       AppInfo instances reconstructed from the on-disk
 *       {@link AppListCache} — those entries fall back to
 *       {@code PackageManager.getActivityIcon(component)}.</li>
 * </ul>
 *
 * <p>This class used to live as a {@code static final} nested class
 * inside {@link LauncherActivity}. Extracted to its own file in v1.4.0
 * so the v1.4.0 cold-start cache helpers
 * ({@link AppListCache#toAppInfo}, {@code IconDiskCache} key derivation)
 * can construct {@link AppInfo} instances without reaching into the
 * activity's nested namespace. Behaviour is identical — the class is
 * still package-private and the constructor is unchanged.
 */
final class AppInfo {
    final String        packageName;
    final String        label;
    /**
     * The label the platform reports (PackageManager / TV Input Framework),
     * before any user rename. Equal to {@link #label} unless the user renamed
     * the app. It is what {@link AppListCache} persists, so the cache stays a
     * pure mirror of the system and a rename (or its reset) never needs a
     * cache rewrite — the override is re-applied wherever an AppInfo is
     * built (see {@link #withRename}).
     *
     * <p>{@link #label} stays {@code final} on purpose: it is read on worker
     * threads (the TV-input tile draws it, the cache writer snapshots it), so
     * a rename publishes a NEW instance via {@link #withLabel} instead of
     * mutating a shared one — no volatile read added to the bind hot path.
     */
    final String        systemLabel;
    final ComponentName component;
    /**
     * The platform {@link ResolveInfo} for this app, when known.
     *
     * <p>Non-final and {@code volatile} so the {@link LauncherActivity}
     * reconcile path can graft a fresh {@code ri} onto a cache-sourced
     * AppInfo that was reconstructed from {@link AppListCache} (which
     * cannot serialise ResolveInfo and therefore stores it as
     * {@code null}). Without the graft, every icon load for that
     * AppInfo falls through to the slower
     * {@link android.content.pm.PackageManager#getActivityIcon(android.content.ComponentName)}
     * binder path forever — even after the post-launch PM scan has
     * resolved the activity.
     *
     * <p>Volatile because the field is read on the
     * {@code iconExecutor} worker thread (inside
     * {@link LauncherActivity}'s {@code resolveIconDrawable}) and
     * written on the UI thread (inside the {@code loadApps} reconcile).
     * The volatile semantics give the worker an immediate visibility
     * guarantee on the upgraded value; absent it, a worker thread
     * already running could observe the stale {@code null} for an
     * arbitrarily long window after the upgrade and still hit the slow
     * fallback. Worst case is identical to the pre-graft behaviour
     * (slow path), but volatile makes the upgrade reliable rather than
     * timing-dependent.
     */
    volatile ResolveInfo ri;

    /**
     * Memoised, ellipsised label as drawn under the shelf cell — lazily
     * computed and cached the first time any {@code CellView} binds this
     * app, then reused by every subsequent bind.
     *
     * <p>The truncation result is a pure function of the label text, the
     * shelf cell's width budget, the label text size, and the typeface —
     * all of which are constant for the activity's lifetime. There is
     * therefore no reason for a recycled cell that scrolls back onto a
     * previously-seen app during a fast fling to re-run
     * {@link android.text.TextUtils#ellipsize} (which allocates a
     * {@code CharSequence} + a {@code String}) or even
     * {@code Paint.measureText}. Caching the result on the model collapses
     * that to one measure + at-most-one ellipsize per app for the whole
     * session.
     *
     * <p>{@code null} means "not computed yet". The field is written and
     * read exclusively on the main/UI thread (inside {@code CellView.bind}
     * and cleared in {@code LauncherActivity.onConfigurationChanged} when a
     * density / font-scale change can move the truncation point), so it
     * needs no {@code volatile} / synchronisation — unlike {@link #ri},
     * which is touched by the icon-decode worker threads.
     */
    String displayLabel;

    /**
     * Non-null only for a TV-input "app" (HDMI 1, AV, tuner, …) surfaced from
     * the platform TV Input Framework. When set, this entry is launched by
     * switching to the passthrough input via {@code TvContract} rather than by
     * starting an activity, its banner tile is a generated input glyph, and
     * the "Uninstall" / "App info" context-menu rows are suppressed (an input
     * is not an installed package). Everything else — ordering, home/drawer
     * placement, reorder, hide, remote-key binding — treats it exactly like a
     * normal app. {@code null} for ordinary apps. See {@link TvInputs}.
     */
    final String tvInputId;

    AppInfo(String pkg, String lbl, ComponentName cmp, ResolveInfo r) {
        this(pkg, lbl, lbl, cmp, r, null);
    }

    private AppInfo(String pkg, String lbl, String sysLbl, ComponentName cmp,
                    ResolveInfo r, String tvInput) {
        packageName = pkg;
        label       = lbl;
        systemLabel = sysLbl;
        component   = cmp;
        ri          = r;
        tvInputId   = tvInput;
    }

    /** Copy of this entry showing {@code shown} as its label. Identity
     *  ({@link #packageName}, {@link #component}, {@link #tvInputId}), the
     *  resolved {@link #ri} and {@link #systemLabel} carry over; the
     *  {@link #displayLabel} memo starts empty so the new text is measured
     *  on the next bind. */
    AppInfo withLabel(String shown) {
        return new AppInfo(packageName, shown, systemLabel, component, ri, tvInputId);
    }

    /** This entry with the user's rename from {@code renames} applied (or
     *  removed, when there is none). Returns {@code this} — no allocation —
     *  when the shown label is already right, which is every app on every
     *  call for a user who never renamed anything. */
    AppInfo withRename(Map<String, String> renames) {
        String want = renames.get(packageName);
        if (want == null) want = systemLabel;
        return want.equals(label) ? this : withLabel(want);
    }

    /** Build a TV-input entry. The synthetic {@link #packageName}
     *  ({@code "tvinput://" + inputId}) is the stable key used everywhere a
     *  package name is — order persistence, the hidden set, the icon/banner
     *  caches, remote-key bindings — so an input round-trips through all of
     *  them like any other app. It can never collide with a real package
     *  (real package names are never of this shape) and contains no comma, so
     *  it is safe inside the comma-separated persisted order string. */
    static AppInfo tvInput(String inputId, String label) {
        return new AppInfo("tvinput://" + inputId, label, label, null, null, inputId);
    }
}
