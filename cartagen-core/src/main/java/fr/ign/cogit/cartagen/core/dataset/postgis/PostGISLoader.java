package fr.ign.cogit.cartagen.core.dataset.postgis;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import org.postgis.PGgeometry;
import org.postgresql.util.PSQLException;

import fr.ign.cogit.cartagen.core.dataset.CartAGenDB;
import fr.ign.cogit.cartagen.core.dataset.CartAGenDataSet;
import fr.ign.cogit.cartagen.core.genericschema.AbstractCreationFactory;
import fr.ign.cogit.cartagen.core.genericschema.IGeneObj;
import fr.ign.cogit.cartagen.core.genericschema.urban.ITown;
import fr.ign.cogit.cartagen.core.genericschema.urban.IUrbanBlock;
import fr.ign.cogit.cartagen.core.persistence.GeOxygeneGeometryUserType;
import fr.ign.cogit.cartagen.mrdb.scalemaster.GeometryType;
import fr.ign.cogit.geoxygene.api.feature.IPopulation;
import fr.ign.cogit.geoxygene.api.spatial.geomaggr.IMultiPrimitive;
import fr.ign.cogit.geoxygene.api.spatial.geomroot.IGeometry;
import fr.ign.cogit.geoxygene.util.conversion.ParseException;
import fr.ign.cogit.geoxygene.util.conversion.WktGeOxygene;

/**
 * 
 * @author MDumont
 * 
 */

public class PostGISLoader {

  private String where = "";
  private String schema;
  private Connection conn;
  private PostGISToLayerMapping mapping;
  private PostGISConnector pgLoader;
  private String dbName;

  // ***************
  // CONSTRUCTOR
  // ***************
  public PostGISLoader(String host, String port, String dbName, String user,
      String password, String schema) {
    this.pgLoader = new PostGISConnector("jdbc:postgresql://" + host + ":"
        + port + "/" + dbName + "?currentSchema=" + schema, user, password);
    this.conn = pgLoader.connectToPostGISDB();
    this.schema = schema;
    this.setDbName(dbName);
  }

  // ***************
  // GETTERS
  // ***************

  // *** getWherePart ***
  public String getWherePart() {
    String wherePart = "";
    if (this.where != null && !this.where.equals("")) {
      wherePart = " where " + where;
    }
    return wherePart;
  }

  // *** getMapping ***
  public PostGISToLayerMapping getMapping() {
    return mapping;
  }

  // ***************
  // SETTERS
  // ***************

  // *** setWhere ***
  public void setWhere(String where) {
    this.where = where;
  }

  // *** setMapping ***
  public void setMapping(PostGISToLayerMapping mapping) {
    this.mapping = mapping;
  }

  // ***************
  // OTHER METHODS
  // ***************

  // *** showTables() ***
  // This method returns the available datasets in the loader database in
  // List<String> format (alphabetic order)

  public List<String> showTables() {
    Statement s = null;
    ResultSet r = null;
    List<String> tablesList = new ArrayList<String>();
    try {
      s = this.conn.createStatement();
    } catch (SQLException e1) {
      e1.printStackTrace();
    }
    try {
      r = s.executeQuery(
          "select * from geometry_columns where (f_geometry_column='geometrie' or f_geometry_column='geom' or f_geometry_column='geometry') and f_table_schema='"
              + this.schema + "'");
      while (r.next()) {
        String table_name = (String) r.getObject(3);
        tablesList.add(table_name);
      }
    } catch (SQLException e1) {
      e1.printStackTrace();
    }
    return tablesList;
  }

  // *** loadData ***
  // this fonction manages the mapping between PostGIS layer and Calac schema
  public void loadData(CartAGenDataSet dataset, String layer,
      boolean createGeoClass) {
    AbstractCreationFactory factory = this.mapping.getGeneObjImplementation()
        .getCreationFactory();
    System.out.println(layer);
    Method method = this.mapping.getCreationMethod(layer);
    Hashtable<String, String> attrMapping = this.mapping.getListAttr(layer);
    String wherePart = getWherePart();
    try {
      doTheActualLoading(conn, dataset, layer, schema, method, wherePart,
          factory, attrMapping, createGeoClass);
      System.out.println("Chargement terminé.");
    } catch (NoSuchFieldException | SecurityException
        | InvocationTargetException e) {
      e.printStackTrace();
    }
  }

  // *** doTheActuelLoading ***
  // Load each dataset in the right population (geometry + attributes)
  @SuppressWarnings("unchecked")
  public void doTheActualLoading(Connection conn, CartAGenDataSet dataset,
      String layer, String schema, Method method, String query,
      AbstractCreationFactory factory, Hashtable<String, String> attrMapping,
      boolean createGeoClass) throws NoSuchFieldException, SecurityException,
      InvocationTargetException {
    try {
      // Query the database
      Statement s = conn.createStatement();
      ResultSet r = s
          .executeQuery("select * from " + schema + "." + layer + query);
      // Create the Calac population
      IGeneObj elementDef = (IGeneObj) method.invoke(factory);
      String elementClass = (String) elementDef.getClass()
          .getField("FEAT_TYPE_NAME").get(null);
      IPopulation<IGeneObj> pop = (IPopulation<IGeneObj>) dataset
          .getCartagenPop(dataset.getPopNameFromObj(elementDef), elementClass);
      // Geom
      IGeometry geom = null;

      int w = 0;
      // Create Calac object for each postGIS object
      while (r.next()) {
        // Get the ¨PostGIS layer geometry
        PGgeometry pgGeom = new PGgeometry();
        try {
          pgGeom = (PGgeometry) r.getObject("geometrie");
        } catch (PSQLException e) {
          pgGeom = (PGgeometry) r.getObject("geom");
        }
        GeOxygeneGeometryUserType convertor = new GeOxygeneGeometryUserType();
        // Convert this geometry into a Java compatible geometry
        geom = convertor.convert2GM_Object(pgGeom);
        // Break done the geometry if it is a multiple geometry
        List<IGeometry> listGeom = new ArrayList<IGeometry>();
        if (geom.isMultiSurface() || geom.isMultiCurve()) {
          IMultiPrimitive<?> multiGeom = ((IMultiPrimitive<?>) geom);
          for (Integer i = 0; i < multiGeom.size(); i++) {
            IGeometry geomPart = multiGeom.get(i);
            listGeom.add(geomPart);
          }
        } else
          listGeom.add(geom);
        // For each part, create a Java element
        for (Integer i = 0; i < listGeom.size(); i++) {
          IGeometry geomListPart = listGeom.get(i);
          // Create the Calac object
          IGeneObj element = (IGeneObj) method.invoke(factory);
          // affecte la géométrie
          element.setGeom(geomListPart);
          // Get the object attributes from postGIS layer to Java class given
          // by attrMapping
          for (String attrJava : attrMapping.keySet()) {
            String attrPostGIS = attrMapping.get(attrJava);
            Object value = null;
            try {
              value = r.getObject(attrPostGIS);
            } catch (PSQLException e) {
              continue;
            }
            if (attrJava.equals("initialGeom")) {
              try {
                element
                    .setInitialGeom(WktGeOxygene.makeGeOxygene((String) value));
              } catch (IllegalArgumentException e) {
                e.printStackTrace();
              } catch (ParseException e) {
                e.printStackTrace();
              }
              continue;
            } else {
              if ((attrJava.equals("eliminated"))
                  && !(value instanceof Boolean)) {
                value = Boolean.parseBoolean((String) value);
              } // else {
              // if (attrJava.equals("importance")
              // && !(value instanceof Integer)) {
              // value = Integer.parseInt((String) value);
              // }
              // }
            }
            try {
              element.setAttribute(attrJava, value);
            } catch (IllegalArgumentException e) {
              e.printStackTrace();
            }

          }
          // if existing towns/blocks
          if (element instanceof ITown)
            ((ITown) element).initComponents();
          else {
            if (element instanceof IUrbanBlock)
              ((IUrbanBlock) element).initComponents();
          }
          // Add this object to the population
          pop.add(element);
        }
        w = w + 1;
      }

      // Get the geometry type
      GeometryType geomType = null;
      if (geom.isPoint())
        geomType = GeometryType.POINT;
      else if (geom.isLineString() || geom.isMultiCurve())
        geomType = GeometryType.LINE;
      else if (geom.isPolygon() || geom.isMultiSurface())
        geomType = GeometryType.POLYGON;

      // Create geoClass
      if (createGeoClass) {
        PostGISClass geoClass = new PostGISClass(layer, elementClass, geomType);
        CartAGenDB database = dataset.getCartAGenDB();
        if (!database.getClasses().contains(geoClass)) {
          database.addClass(geoClass);
        }
      }

      System.out.println("nb d'objets chargés = " + w);
    } catch (SQLException | IllegalArgumentException
        | IllegalAccessException ex) {
      ex.printStackTrace();
    }

  }

  public Connection getConn() {
    return this.conn;
  }

  public String getSchema() {
    return this.schema;
  }

  public String getDbName() {
    return dbName;
  }

  public void setDbName(String dbName) {
    this.dbName = dbName;
  }
}