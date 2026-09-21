package com.miruqa.landing;

import org.apache.catalina.Context;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;

import java.io.File;

/** ローカル確認用の、組み込みTomcat（テストでも使う）。本番は、WARを、Tomcat 10.1のコンテナで動かす。 */
public class DevServer {
    private final Tomcat tomcat = new Tomcat();

    public int start(int port) throws Exception {
        tomcat.setBaseDir(new File("target/tomcat-work").getAbsolutePath());
        tomcat.setPort(port);
        tomcat.getConnector();
        Context ctx = tomcat.addWebapp("", new File("src/main/webapp").getAbsolutePath());
        WebResourceRoot resources = new StandardRoot(ctx);
        resources.addPreResources(new DirResourceSet(resources, "/WEB-INF/classes", new File("target/classes").getAbsolutePath(), "/"));
        ctx.setResources(resources);
        tomcat.start();
        return tomcat.getConnector().getLocalPort();
    }

    public void stop() throws Exception {
        tomcat.stop();
        tomcat.destroy();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8899;
        new DevServer().start(port);
        System.out.println("http://127.0.0.1:" + port + "/");
        Thread.currentThread().join();
    }
}
