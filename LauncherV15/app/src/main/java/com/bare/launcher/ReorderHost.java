package com.bare.launcher;

/**
 * The surface that currently owns the shared reorder context menu (the
 * "Hide / Uninstall / App Info / Rename / Change icon / Move" plate). Either
 * the bottom home shelf ({@code RecyclingShelfView}) or the pull-down app
 * drawer ({@code AppDrawer}) can be the active reorder host at any one time — never
 * both, since the drawer is only reorder-able while it is open (covering the
 * shelf).
 *
 * <p>Introduced in v1.5.0 so the single lazily-built menu overlay
 * ({@code LauncherActivity.ensureMenuOverlay}) and its highlight logic
 * ({@code updateMenuHighlight}) can drive whichever surface is reordering
 * without hard-wiring to the shelf. The activity holds the current host in
 * {@code menuHost}; the menu's click listeners and the highlight painter call
 * through this interface instead of reaching into the shelf directly.
 */
interface ReorderHost {

    /** Current menu cursor — one of the {@code RecyclingShelfView.MENU_*}
     *  row ids. Read by the highlight painter. */
    int menuSelection();

    /** The user activated the Hide row (hides the app from the shelf/drawer;
     *  it stays installed and listed in the Manage-hidden-apps screen). */
    void onMenuHide();

    /** The user activated the Uninstall row (touch click or D-pad confirm). */
    void onMenuUninstall();

    /** The user activated the App Info row. */
    void onMenuAppInfo();

    /** The user activated the Move row (confirm reorder). */
    void onMenuMove();

    /** {@code true} when the app the menu is currently acting on is a TV
     *  input (HDMI/AV/…). The activity uses this to suppress the Uninstall
     *  and App-info rows — an input is not an installed package — while
     *  keeping Move and Hide. */
    boolean menuAppIsInput();

    /** The app the menu is acting on, or {@code null}. The activity uses it
     *  to offer "Reset icon" only for an app that has a custom tile. */
    AppInfo menuApp();

    /** v2.3.0: the user activated Rename (opens the rename card). */
    void onMenuRename();

    /** v2.3.0: the user activated Change icon (opens the image picker). */
    void onMenuChangeIcon();

    /** v2.3.0: the user activated Reset icon (drops the custom tile). */
    void onMenuResetIcon();
}
