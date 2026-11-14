# Widgets and watch tiles

Phone widgets use AppWidgetProvider and RemoteViews with five fixed grids (2x1, 1x2, 2x2, 2x4 and 4x2), capped at two, four or eight entries. The persisted widget flag filters the existing script order. Save, deletion and reordering trigger coalesced updates; there is no periodic refresh or background script execution.

Watch visibility is independent of phone visibility. WatchSync stores the chosen IDs and includes their flags in the next snapshot hash. The watch TileService reads only the local WatchStore and displays at most eight entries after synchronization. Native tinted card drawables avoid bitmap allocation. Arc and icon colors come from the shared script palette. The center uses the application icon.

Both surfaces launch MainActivity before running a script. Phone PendingIntents are immutable. Launch requests carry a local random token; the activity validates it and checks that the script still exists and is enabled. Activity recreation does not replay the request. The tile constructs static host-rendered layouts and never initializes the script runner.
