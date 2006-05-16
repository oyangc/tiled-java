/*
 *  Tiled Map Editor, (c) 2004-2006
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  Adam Turk <aturk@biggeruniverse.com>
 *  Bjorn Lindeijer <b.lindeijer@xs4all.nl>
 */

package tiled.mapeditor.widget;

import java.awt.*;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.event.EventListenerList;
import javax.swing.event.MouseInputAdapter;

import tiled.core.*;
import tiled.mapeditor.util.*;

/**
 * @version $Id$
 */
public class TilePalettePanel extends JPanel implements Scrollable
{
    private static final int TILES_PER_ROW = 4;
    private TileSet tileset;
    private EventListenerList tileSelectionListeners;

    public TilePalettePanel() {
        tileSelectionListeners = new EventListenerList();

        MouseInputAdapter mouseInputAdapter = new MouseInputAdapter() {
            public void mousePressed(MouseEvent e) {
                Tile clickedTile = getTileAtPoint(e.getX(), e.getY());
                if (clickedTile != null) {
                    fireTileSelectionEvent(clickedTile);
                }
            }

            public void mouseDragged(MouseEvent e) {
                mousePressed(e);
            }
        };
        addMouseListener(mouseInputAdapter);
        addMouseMotionListener(mouseInputAdapter);
    }

    /**
     * Adds tile selection listener. The listener will be notified when the
     * user selects a tile.
     */
    public void addTileSelectionListener(TileSelectionListener l) {
        tileSelectionListeners.add(TileSelectionListener.class, l);
    }

    /**
     * Removes tile selection listener.
     */
    public void removeTileSelectionlistener(TileSelectionListener l) {
        tileSelectionListeners.remove(TileSelectionListener.class, l);
    }

    protected void fireTileSelectionEvent(Tile selectedTile) {
        Object[] listeners = tileSelectionListeners.getListenerList();
        TileSelectionEvent event = null;

        for (int i = listeners.length - 2; i >= 0; i -= 2) {
            if (listeners[i] == TileSelectionListener.class) {
                if (event == null) event =
                    new TileSelectionEvent(this, selectedTile);
                ((TileSelectionListener)listeners[i + 1]).tileSelected(event);
            }
        }
    }

    /**
     * Change the tileset displayed by this palette panel.
     */
    public void setTileset(TileSet tileset) {
        this.tileset = tileset;
        revalidate();
        repaint();
    }

    public Tile getTileAtPoint(int x, int y) {
        Tile ret = null;

        if (tileset != null) {
            int twidth = tileset.getTileWidth() + 1;
            int theight = tileset.getTileHeight() + 1;
            int tx = x / twidth;
            int ty = y / theight;
            int tilesPerRow = (getWidth() - 1) / twidth;
            int tileId = ty * tilesPerRow + tx;

            // Now that we're in the right "spot", find the next valid tile
            // todo: Assumes to gab is so big that a tile can be found between the
            // todo: right spot and the tile that was actually clicked, actually
            // todo: I'm not sure whether this works at all. - Bjorn
            while ((ret = tileset.getTile(tileId++)) == null) {
                if (tileId > tileset.getMaxTileId()) break;
            }
        }

        return ret;
    }

    public void paint(Graphics g) {
        Rectangle clip = g.getClipBounds();

        paintBackground(g);

        if (tileset != null) {
            // Draw the tiles
            int twidth = tileset.getTileWidth() + 1;
            int theight = tileset.getTileHeight() + 1;
            int tilesPerRow = Math.max(1, (getWidth() - 1) / twidth);

            int startY = clip.y / theight;
            int endY = (clip.y + clip.height) / theight + 1;
            int tileAt = tilesPerRow * startY;
            int gy = startY * theight;

            for (int y = startY; y < endY && tileAt < tileset.getMaxTileId(); y++) {
                int gx = 1;
                for (int x = 0; x < tilesPerRow; x++) {
                    Tile tile;
                    while ((tile = tileset.getTile(tileAt++)) == null) {
                        if (tileAt > tileset.getMaxTileId() || tileAt-1 == 0) {
                            break;
                        }
                    }

                    if (tile != null) {
                        tile.drawRaw(g, gx, gy + theight, 1.0);
                    }
                    gx += twidth;
                }
                gy += theight;
            }
        }
    }

    /**
     * Draws checkerboard background.
     */
    private static void paintBackground(Graphics g) {
        Rectangle clip = g.getClipBounds();
        int side = 10;

        int startX = clip.x / side;
        int startY = clip.y / side;
        int endX = (clip.x + clip.width) / side + 1;
        int endY = (clip.y + clip.height) / side + 1;

        // Fill with white background
        g.setColor(Color.WHITE);
        g.fillRect(clip.x, clip.y, clip.width, clip.height);

        // Draw darker squares
        g.setColor(Color.LIGHT_GRAY);
        for (int y = startY; y < endY; y++) {
            for (int x = startX; x < endX; x++) {
                if ((y + x) % 2 == 1) {
                    g.fillRect(x * side, y * side, side, side);
                }
            }
        }
    }

    public Dimension getPreferredSize() {
        if (tileset == null) {
            return new Dimension(0, 0);
        }
        else {
            int twidth = tileset.getTileWidth() + 1;
            int theight = tileset.getTileHeight() + 1;
            int tileCount = tileset.size();
            int tilesPerRow = Math.max(1, (getWidth() - 1) / twidth);
            int rows = tileCount / tilesPerRow +
                    (tileCount % tilesPerRow > 0 ? 1 : 0);

            return new Dimension(tilesPerRow * twidth + 1, rows * theight + 1);
        }
    }


    // Scrollable interface

    public Dimension getPreferredScrollableViewportSize() {
        if (tileset != null) {
            int twidth = tileset.getTileWidth() + 1;
            return new Dimension(TILES_PER_ROW * twidth + 1, 200);
        } else {
            return new Dimension(0, 0);
        }
    }

    public int getScrollableUnitIncrement(Rectangle visibleRect,
            int orientation, int direction) {
        if (tileset != null) {
            return tileset.getTileWidth();
        } else {
            return 0;
        }
    }

    public int getScrollableBlockIncrement(Rectangle visibleRect,
            int orientation, int direction) {
        if (tileset != null) {
            return tileset.getTileWidth();
        } else {
            return 0;
        }
    }

    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
