# Bank Tag Folders

Collapsible folders for the bank tag tab column.

If you keep more tag tabs than fit down the side of the bank, this groups them.
Drag one tab onto another to make a folder, name it, and collapse it out of the
way. Everything else about the tag column keeps working exactly as it does now.

## Requirements

This plugin needs the core **Bank Tags** plugin **enabled**, with its own
**"Tag tabs" option switched off**.

Bank Tags still owns your tags, icons and layouts. This plugin only draws the
column, because both strips build their widgets into the same bank container and
would delete each other's on every rebuild. Until you turn that option off, this
plugin stays idle and says so once in chat. It never changes the setting for you.

## Folders

| Gesture | Result |
| --- | --- |
| Drag a tab onto the **middle** of another tab | Prompts for a name, makes a folder from both |
| Drag a tab onto the **top or bottom quarter** of a row | Inserts before or after it, reordering |
| Drag a tab onto the middle of a folder row | Files it into that folder, expanding it |
| Drag a tab past the last row | Sends it to the bottom of the top level |
| Left-click a folder | Expands or collapses it |
| Right-click a folder | Change icon, Rename, Delete folder |
| Right-click a tab inside a folder | Adds "Remove from folder" |

The middle-versus-edge split is what lets one gesture do two jobs, the way phone
home screens do. The edge band is a quarter of the row height at each end, which
is 10px on a stock 40px tab.

Emptying a folder deletes it, whether you drag the last tab out or delete that
tab from the bank. Deleting a folder only removes the grouping; the tabs inside
spill back out where it stood.

## Everything the stock tab strip does

The column is a port of the core plugin's tab strip, not a reduced replacement:

* Mouse-wheel scrolling over the column, scroll arrows, and hold-to-scroll when
  you drag an item onto an arrow
* New tag tab, Import tag tab (both the core format and Bank Tag Layouts'),
  Export tag tab to clipboard, View tag tabs
* Change icon, Rename (including the merge-into-existing-tag flow), Delete
  (tab and tag, tab only, or cancel)
* Enable and disable layout per tab, and the per-tab layout editor
* Drag a bank item onto a tab to tag it; shift-drag to tag every variant
* Reorder by dragging
* Reopens the tag you last had open, honouring Bank Tags' own
  "Remember last tag tab" setting rather than adding a second one
* Column height clamped against the incinerator, the collapse button and the
  storage popup, so rows never run underneath them

## Configuration

* **Tag row height** and **Folder row height** - the game's own tabs are 40px
  tall; smaller values fit more rows on screen.
* **Protect layout spacing** - off by default, matching Bank Tags exactly. When
  a tab has a layout, any tagged item not yet in that layout drops into the first
  free slot, from the top. If you deliberately leave blank slots as spacing, turn
  this on and those items are appended below the layout instead.

## What this plugin touches in the game

Stated plainly, because it is what the Plugin Hub review is for.

**It draws client-side widgets into the bank.** The tag column is built as
dynamic children of the bank's item container, which is exactly what the Bank
Tags plugin bundled with RuneLite already does, in the same container.

**It does not add menu entries.** Options on the column's own widgets are
attached to widgets this plugin created, and are handled entirely inside the
client.

**It sends nothing to the server.** No action is ever issued on your behalf.
The one place this costs something is the potion store: it is a bank tab on the
server rather than just a client-side view, so switching away from it in the
client alone would leave deposits going to it. Rather than do that, the plugin
declines to open a tag while the store is up and says so in chat. Close the
potion store and the tab opens normally. The build asserts that no action is
sent, so this cannot regress.

**It does not unhide interface components, and it does not move or resize the
click zones of the 3D scene, inventory, worn equipment, spellbook or prayer
book.** The bank is the only interface it addresses at all, and the build asserts
that too.

## Data

**This plugin sends no data anywhere.** It makes no network calls and touches no
files. Folder definitions are stored through RuneLite's own configuration, in the
`banktagfolders` group.

Import and export of tag tabs use your system clipboard, which is local to your
machine. Nothing is transmitted off the client.

The build asserts all of this, so the claims cannot quietly go stale.

## How this differs from similar plugins

The hub already has several bank tag plugins, and asks people to extend rather
than fragment. This one occupies a different slot:

* **Bank Tag Layouts** arranges the items *inside* a tab. This arranges the
  *tabs* themselves. They compose, and this plugin is written to keep out of its
  way: it mirrors the tab list into the core `TabManager` precisely so that Bank
  Tag Layouts still sees your tabs and keeps working when the core strip is off.
* **Bank Tab Organizer** and **Bank Tag Generation** create and populate tags.
  This plugin does not create tags; it groups the ones you already have.
* **Expanded Bank Tags Viewer** changes how tags are viewed. This adds hierarchy
  to the tab column.

Extending Bank Tags itself was considered first. It is not workable: the core
strip's `TabManager.save()` writes its in-memory tab list back to
`banktags.tagtabs` whenever a tab is renamed, deleted or reordered, so hiding a
collapsed folder's tabs by removing them from that list would permanently delete
them from the user's configuration. Owning the column outright is the only way to
hide rows safely.

## Your tags are safe

Folder definitions live entirely in this plugin's own config group. Nothing about
them is written into `banktags`, and the core tag list stays the single source of
truth for which tags exist. Disable this plugin and you get your flat tag list
back exactly as it was.

## Building

```
./gradlew build
```

Copy `build/libs/bank-tag-folders-1.0.jar` into
`.runelite/sideloaded-plugins/` to run it locally.

## Licence and credits

BSD 2-Clause. See `LICENSE`.

Parts of the tab strip are derived from the Bank Tags plugin that ships with
RuneLite, which is under the same licence. See `NOTICE` for what is derived and
who wrote it.
