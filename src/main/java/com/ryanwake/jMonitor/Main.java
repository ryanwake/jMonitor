package com.ryanwake.jMonitor;

import com.jcraft.jsch.*;
import com.sun.org.apache.xpath.internal.operations.Bool;
import org.yaml.snakeyaml.Yaml;
import oshi.SystemInfo;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.NetworkIF;
import oshi.software.os.OperatingSystem;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.nio.charset.Charset;
import java.sql.*;
import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Vector;
import java.util.logging.FileHandler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Created by ryan on 9/28/16.
 */


public class Main {
    static Map<String, Object> config = null;
    static Map<String, Object> data = null;
    static Connection conn = null;
    static Logger log = Logger.getLogger("jMonitor");
    static FileHandler fh = null;
    static final int VERSION = 4;

    public static void update(int vers) throws IOException {
        String fileUrl = "http://104.131.38.163/updates/" + vers + "/jMonitor.jar";
        URL url = new URL(fileUrl);

        HttpURLConnection httpConn = (HttpURLConnection) url.openConnection();
        int responseCode = httpConn.getResponseCode();

        // always check HTTP response code first
        if (responseCode == HttpURLConnection.HTTP_OK) {
            String fileName = "";
            String disposition = httpConn.getHeaderField("Content-Disposition");
            String contentType = httpConn.getContentType();
            int contentLength = httpConn.getContentLength();

            if (disposition != null) {
                // extracts file name from header field
                int index = disposition.indexOf("filename=");
                if (index > 0) {
                    fileName = disposition.substring(index + 10,
                            disposition.length() - 1);
                }
            } else {
                // extracts file name from URL
                fileName = fileUrl.substring(fileUrl.lastIndexOf("/") + 1,
                        fileUrl.length());
            }

            System.out.println("Content-Type = " + contentType);
            System.out.println("Content-Disposition = " + disposition);
            System.out.println("Content-Length = " + contentLength);
            System.out.println("fileName = " + fileName);

            // opens input stream from the HTTP connection
            InputStream inputStream = httpConn.getInputStream();
            String saveFilePath = fileName;

            // opens an output stream to save into file
            FileOutputStream outputStream = new FileOutputStream(saveFilePath);

            int bytesRead = -1;
            byte[] buffer = new byte[512];
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }

            outputStream.close();
            inputStream.close();

            System.out.println("File downloaded");
        } else {
            System.out.println("No file to download. Server replied HTTP code: " + responseCode);
        }
        httpConn.disconnect();
    }

    public static String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char)cp);

        }
        return sb.toString();
    }

    public static JSONObject readJsonFromUrl(String url) throws IOException, JSONException {
        InputStream is = new URL(url).openStream();
        try {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            String jsonText = readAll(rd);
            JSONObject json = new JSONObject(jsonText);
            return json;
        } finally {
            is.close();
        }
    }

    public static void checkUpdates() {
        try {
            int vers = -1;
            JSONObject json = readJsonFromUrl("http://104.131.38.163/version.php");
            vers = json.getInt("version");
            if (vers > VERSION) {
                log.warning("Application is out of data.");
                update(vers);
            }

        } catch (IOException ex) {
            log.severe("Error trying to update: " + ex.getLocalizedMessage());
        }

    }

    public static void uploadFile(String[] filenames) {
        String host = "104.131.38.163";
        int port = 22;
        String user = "jmonitor";
        String pass = "BestPassword2016!";
        String workingDir = "/var/www/html/jmonitor/" + data.get("mac");
        SftpATTRS attrs = null;

        Vector<File> files = new Vector<>();
        for (String f : filenames) {
            files.add(new File(f));
        }

        Session session = null;
        Channel channel = null;
        ChannelSftp channelSftp = null;

        try {
            JSch jsch = new JSch();
            session = jsch.getSession(user, host, port);
            session.setPassword(pass);
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect();
            log.info("Connected to SFTP");
            channel = session.openChannel("sftp");
            channel.connect();
            log.info("SFTP channel opened and connected");

            channelSftp = (ChannelSftp) channel;

            try {
                channelSftp.cd(workingDir);
            } catch (SftpException ex) {
                channelSftp.mkdir(workingDir);
                channelSftp.cd(workingDir);
            }
            for (File f : files) {
                channelSftp.put(new FileInputStream(f), f.getName() + "_" + data.get("mac"));
            }
            log.info("File uploaded.");
        } catch (Exception ex) {
            log.warning("Could not upload file. Error: " + ex.getLocalizedMessage());
        } finally {
            try {
                channelSftp.exit();
                channel.disconnect();
                session.disconnect();
            } catch (Exception ex) {
                log.warning("Couldn't close: " + ex.getLocalizedMessage());
            }
        }
    }


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
        data.put("appvers", VERSION);
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
            String query = "{CALL computerInfo(?, ?, ?, ?, ?, ?, ?, ?, ?)}";
            CallableStatement prep = conn.prepareCall(query);
            prep.setString(1, data.get("mac").toString());
            prep.setString(2, data.get("osname").toString());
            prep.setString(3, data.get("osvers").toString());
            prep.setString(4, data.get("osbuild").toString());
            prep.setString(5, data.get("procFam").toString());
            prep.setInt(6, (int) data.get("memory"));
            prep.setString(7, data.get("ip").toString());
            prep.setString(8, data.get("hostname").toString());
            prep.setInt(9, (int)data.get("appvers"));
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
        checkUpdates();
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
        log.info("Application version: " + VERSION);
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
        String[] files = new String[] { "/var/log/system.log", "/usr/local/libexec/jmonitor/jMonitor.log"};


        uploadFile(files);
        long endTime = System.nanoTime();
        long duration = (endTime - startTime) / 1000000;
        log.info("Finished execution in " + duration + " milliseconds.");
    }
}
