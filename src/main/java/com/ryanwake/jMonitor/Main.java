package com.ryanwake.jMonitor;


import org.yaml.snakeyaml.Yaml;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;

import java.io.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.UnknownHostException;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Created by ryan on 9/28/16.
 */
public class Main {
    static Map<String, Object> config = null;
    static Map<String, Object> data = null;
    static Connection conn = null;
    static Logger log = Logger.getLogger("jMonitor");
    static FileHandler fh = null;

    // Use this to export a file from the resources folder to the disk, it will put the file next to the jar file.
    public static String ExportResource(String resourceName) throws Exception {
        InputStream stream = null;
        OutputStream resStreamOut = null;
        String jarFolder;
        try {
            stream = Main.class.getResourceAsStream(resourceName);
            if (stream == null)
                throw new Exception("Cannot get resource: " + resourceName + " from Jar file.");

            int readBytes;
            byte[] buffer = new byte[4096];
            jarFolder = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()).getParentFile().getPath().replace('\\','/');
            resStreamOut = new FileOutputStream(jarFolder + resourceName);
            while ((readBytes = stream.read(buffer)) > 0) {
                resStreamOut.write(buffer, 0, readBytes);
            }

        } catch (Exception ex) {
            throw ex;
        } finally {
            stream.close();
            resStreamOut.close();
        }
        return jarFolder + resourceName;
    }

    public static void readConfig() {
        Yaml yaml = new Yaml();
        Reader reader = null;
        String currentDirectory = System.getProperty("user.dir");
        System.out.println(currentDirectory);
        String inputFileName = currentDirectory+"/config.yaml";
        File configFile = new File(inputFileName);

        try {
            reader = new FileReader(configFile);
            config = (Map<String,Object>) yaml.load(reader);
        } catch (Exception ex) {
            System.out.println("Couldn't load config file: " + ex.getLocalizedMessage());
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (final IOException ioe) {
                    log.severe("Exception caught while cleaning up config file reader: " + ioe);
                }
            }
        }
    }

    public static void getData() {
        SystemInfo si = new SystemInfo();
        HardwareAbstractionLayer hal = si.getHardware();
        OperatingSystem os = si.getOperatingSystem();

        data = new HashMap<String, Object>();

        InetAddress ip = null;
        byte[] mac = null;

        try {
            ip = InetAddress.getLocalHost();
            System.out.println("Current IP address : " + ip.getHostAddress());

            NetworkInterface network = NetworkInterface.getByInetAddress(ip);

            mac = network.getHardwareAddress();
        } catch (Exception ex) {
            log.warning("Could not get IP or Mac address: " + ex.getLocalizedMessage());
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            sb.append(String.format("%02X%s", mac[i], (i < mac.length - 1) ? "-" : ""));
        }

        data.put("mac", sb.toString());
        data.put("osname", os.getVersion().getCodeName());
        data.put("osvers", os.getVersion().getVersion());
        data.put("osbuild", os.getVersion().getBuildNumber());
        data.put("procFam", si.getHardware().getProcessor().getName());
        data.put("memory", (int)((si.getHardware().getMemory().getTotal())/1024/1024/1024));
        try {
            data.put("hostname", InetAddress.getLocalHost().getHostName());
        } catch (Exception e) {
            log.severe("Unable to get hostname");
        }

        data.put("ip", ip.getHostAddress());

        for (Map.Entry<String, Object> entry : data.entrySet())
        {
            System.out.println(entry.getKey() + "/" + entry.getValue());
        }
    }

    public static void insertData() {
        try {
            String query = "{CALL computerInfo(?, ?, ?, ?, ?, ?, ?, ?)}";
            CallableStatement prep = conn.prepareCall(query);
            prep.setString(1, data.get("mac").toString());
            prep.setString(2, data.get("osname").toString());
            prep.setString(3, data.get("osvers").toString());
            prep.setString(4, data.get("osbuild").toString());
            prep.setString(5, data.get("procFam").toString());
            prep.setInt(6, (int) data.get("memory"));
            prep.setString(7, data.get("ip").toString());
            prep.setString(8, data.get("hostname").toString());
            prep.executeQuery();
        } catch (Exception ex) {
            log.severe("Could not insert data to table: " + ex.getLocalizedMessage());
        }
    }

    public static void main (String[] args) {
        // Time how long it takes to run
        long startTime = System.nanoTime();

        // Setup logging
        try {
            File file = new File("./jMonitor.log");
            if (file.exists()) {
                Date date = new Date();
                SimpleDateFormat df = new SimpleDateFormat("yyyy.MM.dd_HH.mm.ss");
                File newName = new File("./jMonitor.log_"+ df.format(date));
                file.renameTo(newName);
            }

            fh = new FileHandler("./jMonitor.log");
            log.addHandler(fh);
            SimpleFormatter formatter = new SimpleFormatter();
            fh.setFormatter(formatter);
            log.info("jMonitor logging started.");
        } catch (Exception ex) {
            ex.printStackTrace();
        }
        File file = new File("./config.yaml");

        // Load the config file in if it exists, else create default and load defaults.
        try {

            String fullPath;
            if (!file.exists()) {
                fullPath = ExportResource("/config.yaml");
                log.info("No previous config.yaml found, creating at: " + fullPath);
                readConfig();
            } else {
                log.info("Reading config file...");
                readConfig();
            }
        } catch (Exception ex) {
            log.severe("Could not load config file! Error message: " + ex.getLocalizedMessage());
        }

        if ((int)config.get("version") == 0) {
            try {
                ExportResource("/config.yaml");
                log.info("Detected version = 0 in config.yaml, overwriting config.yaml.");
            } catch (Exception ex) {

            }
        }
        log.info("Found config of version: " + config.get("version") + " Located at: " + file.getAbsoluteFile());

        try {
            conn = DriverManager.getConnection(config.get("url").toString());
            log.info("Connected to MySQL database.");
            // Do something with the Connection
            // Get data about computer hardware
            getData();

            // Insert the data to MySQL
            insertData();
        } catch (SQLException ex) {
            // handle any errors
            log.severe("Could not connect to MySQL! Error message: " + ex.getMessage() + " - SQLState: " + ex.getSQLState()
                    + " - SQLErrorCode: " + ex.getErrorCode());
        }



        try {
            if (!conn.isClosed())
                conn.close();
        }
        catch (SQLException ex) {
            log.warning("Couldn't close SQL connection. Error message: " + ex.getLocalizedMessage());
        }
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        log.info("Finished execution in " + duration + " milliseconds.");
    }
}
