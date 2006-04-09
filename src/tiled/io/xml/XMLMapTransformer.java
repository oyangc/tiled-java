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

package tiled.io.xml;

import java.awt.Color;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.image.FilteredImageSource;
import java.io.*;
import java.lang.reflect.*;
import java.util.Stack;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Properties;
import java.util.zip.GZIPInputStream;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.xml.parsers.*;

import org.w3c.dom.*;
import org.xml.sax.SAXException;

import tiled.core.*;
import tiled.io.ImageHelper;
import tiled.io.MapReader;
import tiled.mapeditor.util.TransparentImageFilter;
import tiled.mapeditor.util.cutter.BasicTileCutter;
import tiled.util.*;

/**
 * @version $Id$
 */
public class XMLMapTransformer implements MapReader
{
    private Map map;
    private String xmlPath;
    private Stack warnings;

    public XMLMapTransformer() {
        warnings = new Stack();
    }

    private static String makeUrl(String filename) throws MalformedURLException {
        final String url;
        if (filename.indexOf("://") > 0 || filename.startsWith("file:")) {
            url = filename;
        } else {
            url = (new File(filename)).toURL().toString();
        }
        return url;
    }

    private static int reflectFindMethodByName(Class c, String methodName) {
        Method[] methods = c.getMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equalsIgnoreCase(methodName)) {
                return i;
            }
        }
        return -1;
    }

    private void reflectInvokeMethod(Object invokeVictim, Method method,
            String[] args) throws InvocationTargetException, Exception
    {
        Class[] parameterTypes = method.getParameterTypes();
        Object[] conformingArguments = new Object[parameterTypes.length];

        if (args.length < parameterTypes.length) {
            throw new Exception("Insufficient arguments were supplied");
        }

        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].getName().equalsIgnoreCase("int")) {
                conformingArguments[i] = new Integer(args[i]);
            } else if (parameterTypes[i].getName().equalsIgnoreCase("float")) {
                conformingArguments[i] = new Float(args[i]);
            } else if (parameterTypes[i].getName().endsWith("String")) {
                conformingArguments[i] = args[i];
            } else if (parameterTypes[i].getName().equalsIgnoreCase("boolean")) {
                conformingArguments[i] = Boolean.valueOf(args[i]);
            } else {
                warnings.push("INFO: Unsupported argument type " +
                        parameterTypes[i].getName() +
                        ", defaulting to java.lang.String");
                conformingArguments[i] = args[i];
            }
        }

        method.invoke(invokeVictim,conformingArguments);
    }

    private void setOrientation(String o) {
        if (o.equalsIgnoreCase("isometric")) {
            map.setOrientation(Map.MDO_ISO);
        } else if (o.equalsIgnoreCase("orthogonal")) {
            map.setOrientation(Map.MDO_ORTHO);
        } else if (o.equalsIgnoreCase("hexagonal")) {
            map.setOrientation(Map.MDO_HEX);
        } else if (o.equalsIgnoreCase("oblique")) {
            map.setOrientation(Map.MDO_OBLIQUE);
        } else if (o.equalsIgnoreCase("shifted")) {
            map.setOrientation(Map.MDO_SHIFTED);
        } else {
            warnings.push("WARN: Unknown orientation '" + o + "'");
        }
    }

    private static String getAttributeValue(Node node, String attribname) {
        NamedNodeMap attributes = node.getAttributes();
        String att = null;
        if (attributes != null) {
            Node attribute = attributes.getNamedItem(attribname);
            if (attribute != null) {
                att = attribute.getNodeValue();
            }
        }
        return att;
    }

    private static int getAttribute(Node node, String attribname, int def) {
        String attr = getAttributeValue(node, attribname);
        if (attr != null) {
            return Integer.parseInt(attr);
        } else {
            return def;
        }
    }

    private Object unmarshalClass(Class reflector, Node node)
        throws InstantiationException, IllegalAccessException,
               InvocationTargetException {
        Constructor cons = null;
        try {
            cons = reflector.getConstructor(null);
        } catch (SecurityException e1) {
            e1.printStackTrace();
        } catch (NoSuchMethodException e1) {
            e1.printStackTrace();
            return null;
        }
        Object o = cons.newInstance(null);
        Node n;

        Method[] methods = reflector.getMethods();
        NamedNodeMap nnm = node.getAttributes();

        if (nnm != null) {
            for (int i = 0; i < nnm.getLength(); i++) {
                n = nnm.item(i);

                try {
                    int j = reflectFindMethodByName(reflector,
                            "set" + n.getNodeName());
                    if (j >= 0) {
                        reflectInvokeMethod(o,methods[j],
                                new String [] {n.getNodeValue()});
                    } else {
                        warnings.push("WARN: Unsupported attribute '" +
                                n.getNodeName() +
                                "' on <" + node.getNodeName() + "> tag");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return o;
    }

    private Image unmarshalImage(Node t, String baseDir)
        throws MalformedURLException, IOException
    {
        Image img = null;

        String source = getAttributeValue(t, "source");

        if (source != null) {
            if (Util.checkRoot(source)) {
                source = makeUrl(source);
            } else {
                source = makeUrl(baseDir + source);
            }
            img = ImageIO.read(new URL(source));
        } else {
            NodeList nl = t.getChildNodes();

            for (int i = 0; i < nl.getLength(); i++) {
                Node n = nl.item(i);
                if (n.getNodeName().equals("data")) {
                    Node cdata = n.getFirstChild();
                    if (cdata == null) {
                        warnings.push("WARN: image <data> tag enclosed no " +
                                "data. (empty data tag)");
                    } else {
                        String sdata = cdata.getNodeValue();
                        img = ImageHelper.bytesToImage(
                            Base64.decode(sdata.trim().toCharArray()));
                    }
                    break;
                }
            }
        }

        /*
        if (getAttributeValue(t, "set") != null) {
            TileSet ts = (TileSet)map.getTilesets().get(
                    Integer.parseInt(getAttributeValue(t, "set")));
            if (ts != null) {
                ts.addImage(img);
            }
        }
        */

        return img;
    }

    private TileSet unmarshalTilesetFile(InputStream in, String filename)
        throws Exception
    {
        TileSet set = null;
        Node tsNode;
        Document tsDoc = null;

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            DocumentBuilder builder = factory.newDocumentBuilder();
            //builder.setErrorHandler(new XMLErrorHandler());
            tsDoc = builder.parse(in, ".");

            String xmlPathSave = xmlPath;
            if (filename.indexOf(File.separatorChar) >= 0) {
                xmlPath = filename.substring(0,
                        filename.lastIndexOf(File.separatorChar) + 1);
            }

            NodeList tsNodeList = tsDoc.getElementsByTagName("tileset");

            for (int itr = 0; (tsNode = tsNodeList.item(itr)) != null; itr++) {
                set = unmarshalTileset(tsNode);
                if (set.getSource() != null) {
                    warnings.push(
                            "WARN: Recursive external Tilesets are not supported.");
                }
                set.setSource(filename);
                // NOTE: This is a deliberate break. multiple tilesets per TSX are
                // not supported yet (maybe never)...
                break;
            }

            xmlPath = xmlPathSave;
        } catch (SAXException e) {
            warnings.push("ERROR: Failed while loading "+filename+": "+e.getMessage());
            //e.printStackTrace();
        }

        return set;
    }

    private TileSet unmarshalTileset(Node t) throws Exception {
        String source = getAttributeValue(t, "source");
        String basedir = getAttributeValue(t, "basedir");
        int firstGid = getAttribute(t, "firstgid", 1);

        String tilesetBaseDir = xmlPath;

        if (basedir != null) {
            tilesetBaseDir = basedir; //makeUrl(basedir);
        }

        if (source != null) {
            String filename = tilesetBaseDir + source;
            //if (Util.checkRoot(source)) {
            //    filename = makeUrl(source);
            //}

            TileSet ext = null;

            try {
                //just a little check for tricky people...
                String extention = source.substring(source.lastIndexOf('.') + 1);
                if (!extention.toLowerCase().equals("tsx")) {
                    warnings.push("WARN: tileset files should end in .tsx! ("+source+")");
                }

                InputStream in = new URL(makeUrl(filename)).openStream();
                ext = unmarshalTilesetFile(in, filename);
            } catch (FileNotFoundException fnf) {
                warnings.push("ERROR: Could not find external tileset file " +
                        filename);
            }

            if (ext == null) {
                warnings.push("ERROR: tileset "+source+" was not loaded correctly!");
                ext = new TileSet();
            }

            ext.setFirstGid(firstGid);
            return ext;
        }
        else {
            int tileWidth = getAttribute(t, "tilewidth", map != null ? map.getTileWidth() : 0);
            int tileHeight = getAttribute(t, "tileheight", map != null ? map.getTileHeight() : 0);
            int tileSpacing = getAttribute(t, "spacing", 0);

            TileSet set = new TileSet();

            set.setName(getAttributeValue(t, "name"));
            set.setBaseDir(basedir);
            set.setFirstGid(firstGid);

            boolean hasTileElements = false;
            NodeList children = t.getChildNodes();

            // Do an initial pass to see if any tile elements are specified
            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);
                if (child.getNodeName().equalsIgnoreCase("tile")) {
                    hasTileElements = true;
                }
            }

            for (int i = 0; i < children.getLength(); i++) {
                Node child = children.item(i);

                if (child.getNodeName().equalsIgnoreCase("tile")) {
                    set.addTile(unmarshalTile(set, child, tilesetBaseDir));
                }
                else if (child.getNodeName().equalsIgnoreCase("image")) {
                    String imgSource = getAttributeValue(child, "source");
                    String id = getAttributeValue(child, "id");
                    String transStr = getAttributeValue(child, "trans");

                    if (imgSource != null && id == null) {
                        // Not a shared image, but an entire set in one image
                        // file

                        // FIXME: importTileBitmap does not fully support URLs
                        String sourcePath = imgSource;
                        if (!Util.checkRoot(imgSource)) {
                            sourcePath = tilesetBaseDir + imgSource;
                        }

                        if (transStr != null) {
                            // In this case, the tileset image needs special
                            // handling for transparency
                            Color color = new Color(
                                    Integer.parseInt(transStr, 16));
                            Toolkit tk = Toolkit.getDefaultToolkit();
                            try {
                                Image orig = ImageIO.read(new File(sourcePath));
                                Image trans = tk.createImage(
                                        new FilteredImageSource(orig.getSource(),
                                            new TransparentImageFilter(
                                                color.getRGB())));
                                BufferedImage img = new BufferedImage(
                                        trans.getWidth(null),
                                        trans.getHeight(null),
                                        BufferedImage.TYPE_INT_ARGB);

                                img.getGraphics().drawImage(trans, 0, 0, null);

                                set.importTileBitmap(img, new BasicTileCutter(
                                            tileWidth, tileHeight, tileSpacing, 0),
                                        !hasTileElements);

                                set.setTransparentColor(color);
                                set.setTilesetImageFilename(sourcePath);
                            } catch (IIOException iioe) {
                                warnings.push("ERROR: " +
                                        iioe.getLocalizedMessage() + " (" +
                                        sourcePath + ")");
                            }
                        } else {
                            set.importTileBitmap(sourcePath,
                                    new BasicTileCutter(tileWidth, tileHeight,
                                            tileSpacing, 0),
                                    !hasTileElements);
                        }

                    } else {
                        set.addImage(unmarshalImage(child, tilesetBaseDir),
                                Integer.parseInt(getAttributeValue(child, "id")));
                    }
                }
            }

            return set;
        }
    }

    private MapObject unmarshalObject(Node t) throws Exception {
        MapObject obj = null;
        try {
            obj = (MapObject)unmarshalClass(MapObject.class, t);
        } catch (Exception e) {
            e.printStackTrace();
            return obj;
        }

        Properties objProps = obj.getProperties();
        NodeList children = t.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeName().equalsIgnoreCase("property")) {
                objProps.setProperty(
                        getAttributeValue(child, "name"),
                        getAttributeValue(child, "value"));
            }
        }
        return obj;
    }

    private Tile unmarshalTile(TileSet set, Node t, String baseDir)
        throws Exception
    {
        Tile tile = null;
        NodeList children = t.getChildNodes();
        boolean isAnimated = false;

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeName().equalsIgnoreCase("animation")) {
                isAnimated = true;
                break;
            }
        }

        try {
            if (isAnimated) {
                tile = (Tile)unmarshalClass(AnimatedTile.class, t);
            } else {
                tile = (Tile)unmarshalClass(Tile.class, t);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return tile;
        }

        tile.setTileSet(set);

        Properties tileProps = tile.getProperties();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeName().equalsIgnoreCase("image")) {
                int id = getAttribute(child, "id", -1);
                if (id < 0) {
                    id = set.addImage(unmarshalImage(child, baseDir));
                }
                tile.setImage(id);
            } else if (child.getNodeName().equalsIgnoreCase("property")) {
                tileProps.setProperty(
                        getAttributeValue(child, "name"),
                        getAttributeValue(child, "value"));
            } else if (child.getNodeName().equalsIgnoreCase("animation")) {
                // TODO: fill this in once XMLMapWriter is complete
            }
        }

        return tile;
    }

    private MapLayer unmarshalObjectGroup(Node t) throws Exception {
        ObjectGroup og = null;
        try {
            og = (ObjectGroup)unmarshalClass(ObjectGroup.class, t);
        } catch (Exception e) {
            e.printStackTrace();
            return og;
        }

        //Read all objects from the group, "...and in the darkness bind them."
        NodeList children = t.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeName().equalsIgnoreCase("object")) {
                og.bindObject(unmarshalObject(child));
            }
        }

        return og;
    }

    /**
     * Loads a map layer from a layer node.
     */
    private MapLayer readLayer(Node t) throws Exception {
        int layerWidth = getAttribute(t, "width", map.getWidth());
        int layerHeight = getAttribute(t, "height", map.getHeight());

        TileLayer ml = new TileLayer(layerWidth, layerHeight);

        int offsetX = getAttribute(t, "x", 0);
        int offsetY = getAttribute(t, "y", 0);
        int visible = getAttribute(t, "visible", 1);
        String opacity = getAttributeValue(t, "opacity");

        ml.setOffset(offsetX, offsetY);
        ml.setName(getAttributeValue(t, "name"));
        ml.setVisible(visible == 1);

        if (opacity != null) {
            ml.setOpacity(Float.parseFloat(opacity));
        }

        Properties mlProps = ml.getProperties();

        for (Node child = t.getFirstChild(); child != null;
                child = child.getNextSibling())
        {
            if (child.getNodeName().equalsIgnoreCase("data")) {
                String encoding = getAttributeValue(child, "encoding");

                if (encoding != null && encoding.equalsIgnoreCase("base64")) {
                    Node cdata = child.getFirstChild();
                    if (cdata == null) {
                        warnings.push("WARN: layer <data> tag enclosed no data. (empty data tag)");
                    } else {
                        char[] enc = cdata.getNodeValue().trim().toCharArray();
                        byte[] dec = Base64.decode(enc);
                        ByteArrayInputStream bais = new ByteArrayInputStream(dec);
                        InputStream is;

                        String comp = getAttributeValue(child, "compression");

                        if (comp != null && comp.equalsIgnoreCase("gzip")) {
                            is = new GZIPInputStream(bais);
                        } else {
                            is = bais;
                        }

                        for (int y = 0; y < ml.getHeight(); y++) {
                            for (int x = 0; x < ml.getWidth(); x++) {
                                int tileId = 0;
                                tileId |= is.read();
                                tileId |= is.read() <<  8;
                                tileId |= is.read() << 16;
                                tileId |= is.read() << 24;

                                TileSet ts = map.findTileSetForTileGID(tileId);
                                if (ts != null) {
                                    ml.setTileAt(x, y,
                                            ts.getTile(tileId - ts.getFirstGid()));
                                } else {
                                    ml.setTileAt(x, y, null);
                                }
                            }
                        }
                    }
                } else {
                    int x = 0, y = 0;
                    for (Node dataChild = child.getFirstChild();
                            dataChild != null;
                            dataChild = dataChild.getNextSibling())
                    {
                        if (dataChild.getNodeName().equalsIgnoreCase("tile")) {
                            int tileId = getAttribute(dataChild, "gid", -1);
                            TileSet ts = map.findTileSetForTileGID(tileId);
                            if (ts != null) {
                                ml.setTileAt(x, y,
                                        ts.getTile(tileId - ts.getFirstGid()));
                            } else {
                                ml.setTileAt(x, y, null);
                            }

                            x++;
                            if (x == ml.getWidth()) {
                                x = 0; y++;
                            }
                            if (y == ml.getHeight()) { break; }
                        }
                    }
                }
            } else if (child.getNodeName().equalsIgnoreCase("property")) {
                mlProps.setProperty(getAttributeValue(child, "name"),
                        getAttributeValue(child, "value"));
            }
        }

        return ml;
    }

    private void buildMap(Document doc) throws Exception {
        Node item, mapNode;

        mapNode = doc.getDocumentElement();

        if (!mapNode.getNodeName().equals("map")) {
            throw new Exception("Not a valid tmx map file.");
        }

        // Get the map dimensions and create the map
        int mapWidth = getAttribute(mapNode, "width", 0);
        int mapHeight = getAttribute(mapNode, "height", 0);

        if (mapWidth > 0 && mapHeight > 0) {
            map = new Map(mapWidth, mapHeight);
        } else {
            // Maybe this map is still using the dimensions element
            NodeList l = doc.getElementsByTagName("dimensions");
            for (int i = 0; (item = l.item(i)) != null; i++) {
                if (item.getParentNode() == mapNode) {
                    mapWidth = getAttribute(item, "width", 0);
                    mapHeight = getAttribute(item, "height", 0);

                    if (mapWidth > 0 && mapHeight > 0) {
                        map = new Map(mapWidth, mapHeight);
                    }
                }
            }
        }

        if (map == null) {
            throw new Exception("Couldn't locate map dimensions.");
        }

        // Load other map attributes
        String orientation = getAttributeValue(mapNode, "orientation");
        int tileWidth = getAttribute(mapNode, "tilewidth", 0);
        int tileHeight = getAttribute(mapNode, "tileheight", 0);

        if (tileWidth > 0) {
            map.setTileWidth(tileWidth);
        }
        if (tileHeight > 0) {
            map.setTileHeight(tileHeight);
        }

        if (orientation != null) {
            setOrientation(orientation);
        } else {
            setOrientation("orthogonal");
        }

        Properties mapProps = map.getProperties();

        // Load the tilesets, properties, layers and objectgroups
        for (Node sibs = mapNode.getFirstChild(); sibs != null;
                sibs = sibs.getNextSibling())
        {
            if (sibs.getNodeName().equals("tileset")) {
                map.addTileset(unmarshalTileset(sibs));
            }
            else if (sibs.getNodeName().equals("property")) {
                mapProps.setProperty(getAttributeValue(sibs, "name"),
                        getAttributeValue(sibs, "value"));
            }
            else if (sibs.getNodeName().equals("layer")) {
                MapLayer layer = readLayer(sibs);
                if (layer != null) {
                    map.addLayer(layer);
                }
            }
            else if (sibs.getNodeName().equals("objectgroup")) {
                MapLayer layer = unmarshalObjectGroup(sibs);
                if (layer != null) {
                    map.addLayer(layer);
                }
            }
        }
    }

    private Map unmarshal(InputStream in) throws IOException, Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        Document doc;
        try {
            factory.setIgnoringComments(true);
            factory.setIgnoringElementContentWhitespace(true);
            factory.setExpandEntityReferences(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            doc = builder.parse(in, xmlPath);
        } catch (SAXException e) {
            e.printStackTrace();
            throw new Exception("Error while parsing map file: " +
                    e.toString());
        }

        buildMap(doc);
        return map;
    }


    // MapReader interface

    public Map readMap(String filename) throws Exception {
        xmlPath = filename.substring(0,
                filename.lastIndexOf(File.separatorChar) + 1);

        String xmlFile = makeUrl(filename);
        //xmlPath = makeUrl(xmlPath);

        URL url = new URL(xmlFile);
        InputStream is = url.openStream();

        // Wrap with GZIP decoder for .tmx.gz files
        if (filename.endsWith(".gz")) {
            is = new GZIPInputStream(is);
        }

        Map unmarshalledMap = unmarshal(is);
        unmarshalledMap.setFilename(filename);

        return unmarshalledMap;
    }

    public Map readMap(InputStream in) throws Exception {
        xmlPath = makeUrl(".");

        Map unmarshalledMap = unmarshal(in);

        //unmarshalledMap.setFilename(xmlFile)
        //
        return unmarshalledMap;
    }

    public TileSet readTileset(String filename) throws Exception {
        String xmlFile = filename;

        xmlPath = filename.substring(0,
                filename.lastIndexOf(File.separatorChar) + 1);

        xmlFile = makeUrl(xmlFile);
        xmlPath = makeUrl(xmlPath);

        URL url = new URL(xmlFile);
        return unmarshalTilesetFile(url.openStream(), filename);
    }

    public TileSet readTileset(InputStream in) throws Exception {
        // TODO: The MapReader interface should be changed...
        return unmarshalTilesetFile(in, ".");
    }

    /**
     * @see tiled.io.MapReader#getFilter()
     */
    public String getFilter() throws Exception {
        return "*.tmx,*.tmx.gz,*.tsx";
    }

    public String getPluginPackage() {
        return "Tiled internal TMX reader/writer";
    }

    /**
     * @see tiled.io.PluggableMapIO#getDescription()
     */
    public String getDescription() {
        return "This is the core Tiled TMX format reader\n" +
            "\n" +
            "Tiled Map Editor, (c) 2004-2006\n" +
            "Adam Turk\n" +
            "Bjorn Lindeijer";
    }

    public String getName() {
        return "Default Tiled XML (TMX) map reader";
    }

    public boolean accept(File pathname) {
        try {
            String path = pathname.getCanonicalPath();
            if (path.endsWith(".tmx") || path.endsWith(".tsx") ||
                        path.endsWith(".tmx.gz")) {
                return true;
            }
        } catch (IOException e) {}
        return false;
    }

    public void setErrorStack(Stack es) {
        warnings = es;
    }
}
