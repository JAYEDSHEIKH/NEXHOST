package com.mojolauncher.server;

import com.mojolauncher.server.model.ServerInstance;
import com.mojolauncher.server.model.ServerType;

import java.io.*;
import java.nio.file.*;
import java.util.List;

/**
 * Integration test: verifies directory creation, metadata save/load, and EULA writing.
 * Run with: java -cp out com.mojolauncher.server.ServerInstanceTest
 */
public class ServerInstanceTest {

    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        File tmpBase = Files.createTempDirectory("mojo-test").toFile();
        try {
            testInstanceCreation(tmpBase);
            testMetaSaveLoad(tmpBase);
            testEulaRequiresAcceptance(tmpBase);
            testEulaWritten(tmpBase);
            testLoadAll(tmpBase);
        } finally {
            deleteRecursively(tmpBase);
        }
        System.out.printf("%n=== Results: %d passed, %d failed ===%n", passed, failed);
        if (failed > 0) System.exit(1);
    }

    static void testInstanceCreation(File base) throws Exception {
        System.out.print("testInstanceCreation ... ");
        ServerInstance inst = new ServerInstance("test1", base.getAbsolutePath(), ServerType.PAPER, "1.21.4");
        inst.setAcceptedEula(true);
        inst.initDirectories();
        inst.writeEula();
        inst.saveMeta();
        assert new File(inst.getPath()).isDirectory() : "instance dir missing";
        assert new File(inst.getPath(), "eula.txt").exists() : "eula.txt missing";
        assert new File(inst.getPath(), "server.properties").exists() : "server.properties missing";
        assert new File(inst.getPath(), "world").isDirectory() : "world/ missing";
        assert new File(inst.getPath(), "logs").isDirectory() : "logs/ missing";
        assert new File(inst.getPath(), "backups").isDirectory() : "backups/ missing";
        assert new File(inst.getPath(), "plugins").isDirectory() : "plugins/ missing";
        String eulaContent = new String(Files.readAllBytes(Paths.get(inst.getPath(), "eula.txt")));
        assert eulaContent.contains("eula=true") : "eula=true not written";
        pass();
    }

    static void testMetaSaveLoad(File base) throws Exception {
        System.out.print("testMetaSaveLoad ... ");
        ServerInstance orig = new ServerInstance("meta-test", base.getAbsolutePath(), ServerType.VANILLA, "1.20.4");
        orig.setXms("256M");
        orig.setXmx("512M");
        orig.setPort(25570);
        orig.setAcceptedEula(true);
        orig.initDirectories();
        orig.saveMeta();
        ServerInstance loaded = ServerInstance.loadMeta(orig.getPath());
        assert orig.getId().equals(loaded.getId()) : "id mismatch";
        assert orig.getName().equals(loaded.getName()) : "name mismatch";
        assert orig.getServerType() == loaded.getServerType() : "type mismatch";
        assert orig.getMcVersion().equals(loaded.getMcVersion()) : "version mismatch";
        assert orig.getXms().equals(loaded.getXms()) : "xms mismatch";
        assert orig.getXmx().equals(loaded.getXmx()) : "xmx mismatch";
        assert orig.getPort() == loaded.getPort() : "port mismatch";
        assert loaded.isAcceptedEula() : "eula flag not persisted";
        pass();
    }

    static void testEulaRequiresAcceptance(File base) throws Exception {
        System.out.print("testEulaRequiresAcceptance ... ");
        ServerInstance inst = new ServerInstance("eula-reject", base.getAbsolutePath(), ServerType.PAPER, "1.21.4");
        inst.setAcceptedEula(false);
        inst.initDirectories();
        try {
            inst.writeEula();
            fail("Expected IllegalStateException when EULA not accepted");
            return;
        } catch (IllegalStateException e) {
            // expected
        }
        pass();
    }

    static void testEulaWritten(File base) throws Exception {
        System.out.print("testEulaWritten ... ");
        ServerInstance inst = new ServerInstance("eula-ok", base.getAbsolutePath(), ServerType.PAPER, "1.21.4");
        inst.setAcceptedEula(true);
        inst.initDirectories();
        inst.writeEula();
        String content = new String(Files.readAllBytes(Paths.get(inst.getPath(), "eula.txt")));
        assert content.contains("eula=true") : "eula.txt does not contain eula=true";
        pass();
    }

    static void testLoadAll(File base) throws Exception {
        System.out.print("testLoadAll ... ");
        List<ServerInstance> all = ServerInstance.loadAll(base.getAbsolutePath());
        assert all.size() >= 2 : "expected at least 2 instances, got " + all.size();
        pass();
    }

    private static void pass() {
        System.out.println("PASS");
        passed++;
    }

    private static void fail(String msg) {
        System.out.println("FAIL: " + msg);
        failed++;
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) for (File c : f.listFiles()) deleteRecursively(c);
        f.delete();
    }
}
