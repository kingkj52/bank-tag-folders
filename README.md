# Bank Tag Folders

Collapsible folders for the bank tag tab column.

If you keep more tag tabs than fit down the side of the bank, this groups them.
Drag one tab onto another to make a folder, name it, and collapse it out of the
way. Everything else about the tag column keeps working exactly as it does now,
because it is still the Bank Tags column underneath.

## Requirements

This plugin needs the core **Bank Tags** plugin **enabled**, with its **"Tag
tabs" option on**. That is the default, so there is normally nothing to change.

Bank Tags still draws the column and owns your tags, icons and layouts. This
plugin runs after it on the same bank rebuild and rearranges what it drew: it
hides the tabs inside closed folders, adds a header row for each folder, and
restacks the rest. With "Tag tabs" off there is no column to put folders in, so
this plugin stays idle and says so once in chat. It never changes the setting
for you.

## Folders

| Gesture | Result |
| --- | --- |
| Drag a tab onto the **middle** of another tab | Prompts for a name, makes a folder from both |
| Drag a tab onto the **top or bottom quarter** of a row | Inserts before or after it, reordering |
| Drag a tab onto the middle of a folder row | Files it into that folder, expanding it |
| Drag a tab past the last row | Sends it to the bottom of the top level |
| Left-click a folder | Expands or collapses it |
| Right-click a folder | Change icon, Set colour, Rename, Delete folder |
| Right-click a tab inside a folder | Adds "Remove from folder" |
| Right-click the new tab button | Adds "New folder", for an empty folder to drag tabs into |

The middle-versus-edge split is what lets one gesture do two jobs, the way phone
home screens do. The edge band is a quarter of the row height at each end, which
is 10px on a stock 40px tab.

Every folder gets a colour, and the tabs inside it are tinted to match. That is
what marks a tab as belonging to a folder. Right-click a folder and pick
**Set colour** to type your own as hex, in either `3a6b8b` or `#3a6b8b` form,
with `3ab` shorthand also accepted. New folders are given a colour from a
built-in palette, so they are distinguishable straight away.

Emptying a folder deletes it, whether you drag the last tab out or delete that
tab from the bank. Deleting a folder only removes the grouping; the tabs inside
spill back out where it stood.

## Everything else is Bank Tags

Because the tabs are Bank Tags' own, all of this works as it always has, with no
copy of it in this plugin:

* New tag tab, Import tag tab, Export tag tab, View tag tabs
* Change icon, Rename (including the merge-into-existing-tag flow), Delete
* Enable and disable layout per tab, and the per-tab layout editor
* Drag a bank item onto a tab to tag it; shift-drag to tag every variant
* Reopening the last tag, honouring Bank Tags' own "Remember last tag tab"
  setting. If that tag is inside a closed folder, the folder is opened and
  scrolled to so the tab is visibly the active one.
* The bank title, including for plugins such as Inventory Setups that open a tag
  under a name they do not want shown
* Other plugins that read the tab list, such as Bank Tag Layouts

What this plugin takes over is scrolling, since Bank Tags counts tabs and folders
change what a row is: the mouse wheel over the column, the scroll arrows, and
holding a dragged item over an arrow all scroll by row. The column is also kept
clear of the incinerator, the collapse button and the storage popup, using the
space Bank Tags has already worked out.

## Configuration

* **Tag row height** - applies to folders too. 24 to 40, capped because the
  game's tab background sprite is itself 40px tall and repeats above that.
  Smaller values fit more rows on screen.
* **Tag tint strength** - how strongly a folder's colour tints the tag tabs
  inside it. Zero leaves them looking like ordinary tabs.
* **Folder tint strength** - how strongly the colour fills the folder's own row,
  tuned separately from the tabs inside it.
* **Protect layout spacing** - off by default, matching Bank Tags exactly. When
  a tab has a layout, any tagged item not yet in that layout drops into the first
  free slot, from the top. If you deliberately leave blank slots as spacing, turn
  this on and those items are appended below the layout instead. This is the one
  case where this plugin opens a tag itself rather than leaving the click to Bank
  Tags.

## What this plugin touches in the game

Stated plainly, because it is what the Plugin Hub review is for.

**It rearranges client-side widgets in the bank.** The tag column is the dynamic
children of the bank's item container that the Bank Tags plugin bundled with
RuneLite builds. This plugin moves and hides those, and adds a few of its own
beside them for the folder rows and tinting.

**It does not add menu entries.** The two options it adds, "Remove from folder"
and "New folder", are options on widgets of that same column, and clicking them
is handled entirely inside the client. Folder rows work the same way.

**It sends nothing to the server.** No action is ever issued on your behalf.
If the potion store is open and Protect layout spacing is on, it will refuse to
switch tags and send a message about closing the store first.

**It does not unhide interface components, and it does not move or resize the
click zones of the 3D scene, inventory, worn equipment, spellbook or prayer
book.** The bank is the only interface it addresses at all, and the build asserts
that too.

## Data

**This plugin sends no data anywhere.** It makes no network calls and touches no
files. Folder definitions are stored through RuneLite's own configuration, in the
`banktagfolders` group.

The build asserts all of this, so the claims cannot quietly go stale.

## How this differs from similar plugins

The hub already has several bank tag plugins, and asks people to extend rather
than fragment. This one occupies a different slot:

* **Bank Tag Layouts** arranges the items *inside* a tab. This arranges the
  *tabs* themselves. They compose, and nothing here is in its way: the core tab
  list it reads is never touched.
* **Bank Tab Organizer** and **Bank Tag Generation** create and populate tags.
  This plugin does not create tags; it groups the ones you already have.
* **Expanded Bank Tags Viewer** changes how tags are viewed. This adds hierarchy
  to the tab column.

## Your tags are safe

Folder definitions live entirely in this plugin's own config group. Nothing is
ever written into `banktags`, not the tab list, the icons or the layouts, and
the build asserts that too. The core tag list stays the single source of truth
for which tags exist.

Disable this plugin and you get your flat tag list back exactly as it was. The order
you arrange tabs in while it is on is kept in this plugin's own config group, so it
does not carry across to the flat list. Hiding a collapsed folder's tabs is done to
the widgets on screen and never to the list Bank Tags saves, so there is nothing
for a rename, delete or reorder to write back wrongly.

## Building

```
./gradlew build
```

Copy `build/libs/bank-tag-folders-2.0.jar` into
`.runelite/sideloaded-plugins/` to run it locally.

## Licence and credits

BSD 2-Clause. See `LICENSE`.

This plugin is written against the Bank Tags plugin that ships with RuneLite,
which is under the same licence. See `NOTICE` for what is derived and who wrote
it.
