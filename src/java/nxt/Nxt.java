package nxt;

import nxt.http.API;
import nxt.peer.Peers;
import nxt.user.Users;
import nxt.util.Logger;
import nxt.util.ThreadPool;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public final class Nxt {

    public static final String NXT_VERSION = "1.2.6";
    public static final String APPLICATION = "FIMK";
    
    /* XXX - This tracks the FIM version */
    public static final String VERSION = "0.3.3";

    private static final Properties defaultProperties = new Properties();
    static {
        System.out.println("Initializing FIM server version " + Nxt.VERSION + " (based on NXT " + NXT_VERSION + ")");
        try (InputStream is = ClassLoader.getSystemResourceAsStream("nxt-default.properties")) {
            if (is != null) {
                Nxt.defaultProperties.load(is);
            } else {
                String configFile = System.getProperty("nxt-default.properties");
                if (configFile != null) {
                    try (InputStream fis = new FileInputStream(configFile)) {
                        Nxt.defaultProperties.load(fis);
                    } catch (IOException e) {
                        throw new RuntimeException("Error loading nxt-default.properties from " + configFile);
                    }
                } else {
                    throw new RuntimeException("nxt-default.properties not in classpath and system property nxt-default.properties not defined either");
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Error loading nxt-default.properties", e);
        }
    }
    private static final Properties properties = new Properties(defaultProperties);
    static {
        try (InputStream is = ClassLoader.getSystemResourceAsStream("nxt.properties")) {
            if (is != null) {
                Nxt.properties.load(is);
            } // ignore if missing
        } catch (IOException e) {
            throw new RuntimeException("Error loading nxt.properties", e);
        }
    }

    private static int displayProperties = 0;
    private static boolean getDisplayProperties() {
      if (displayProperties == 0) {
        String value = properties.getProperty("nxt.debug");
        displayProperties = Boolean.TRUE.toString().equals(value) ? 1 : 2;
      }
      return displayProperties == 1;
    }
    
    public static int getIntProperty(String name) {
        try {
            int result = Integer.parseInt(properties.getProperty(name));
            if (getDisplayProperties()) Logger.logMessage(name + " = \"" + result + "\"");
            return result;
        } catch (NumberFormatException e) {
            if (getDisplayProperties()) Logger.logMessage(name + " not defined, assuming 0");
            return 0;
        }
    }

    public static String getStringProperty(String name) {
        return getStringProperty(name, null);
    }

    public static String getStringProperty(String name, String defaultValue) {
        String value = properties.getProperty(name);
        if (value != null && ! "".equals(value)) {
            if (getDisplayProperties()) Logger.logMessage(name + " = \"" + value + "\"");
            return value;
        } else {
            if (getDisplayProperties()) Logger.logMessage(name + " not defined");
            return defaultValue;
        }
    }

    public static List<String> getStringListProperty(String name) {
        String value = getStringProperty(name);
        if (value == null || value.length() == 0) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String s : value.split(";")) {
            s = s.trim();
            if (s.length() > 0) {
                result.add(s);
            }
        }
        return result;
    }

    public static Boolean getBooleanProperty(String name) {
        String value = properties.getProperty(name);
        if (Boolean.TRUE.toString().equals(value)) {
            if (getDisplayProperties()) Logger.logMessage(name + " = \"true\"");
            return true;
        } else if (Boolean.FALSE.toString().equals(value)) {
            if (getDisplayProperties()) Logger.logMessage(name + " = \"false\"");
            return false;
        }
        if (getDisplayProperties()) Logger.logMessage(name + " not defined, assuming false");
        return false;
    }

    public static Blockchain getBlockchain() {
        return BlockchainImpl.getInstance();
    }

    public static BlockchainProcessor getBlockchainProcessor() {
        return BlockchainProcessorImpl.getInstance();
    }

    public static TransactionProcessor getTransactionProcessor() {
        return TransactionProcessorImpl.getInstance();
    }

    public static void main(String[] args) {
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                Nxt.shutdown();
            }
        }));
        init();
    }

    public static void init(Properties customProperties) {
        properties.putAll(customProperties);
        init();
    }

    public static void init() {
        Init.init();
    }

    public static void shutdown() {
        API.shutdown();
        Users.shutdown();
        Peers.shutdown();
        TransactionProcessorImpl.getInstance().shutdown();
        ThreadPool.shutdown();
        Db.shutdown();
        Logger.logMessage("FIM server " + VERSION + " (based on NXT "+ NXT_VERSION + ") stopped.");
        Logger.shutdown();
    }

    private static class Init {

        static {

            long startTime = System.currentTimeMillis();
            Logger.init();
            Db.init();
            BlockchainProcessorImpl.getInstance();
            TransactionProcessorImpl.getInstance();
            Peers.init();
            Generator.init();
            API.init();
            Users.init();
            DebugTrace.init();
            ThreadPool.start();

            long currentTime = System.currentTimeMillis();
            Logger.logDebugMessage("Initialization took " + (currentTime - startTime) / 1000 + " seconds");
            Logger.logMessage("FIM server " + VERSION + " (based on NXT "+ NXT_VERSION + ") started successfully.");
            if (Constants.isTestnet) {
                Logger.logMessage("RUNNING ON TESTNET - DO NOT USE REAL ACCOUNTS!");
            }
        }

        private static void init() {}

        private Init() {} // never

    }

    private Nxt() {} // never

}
