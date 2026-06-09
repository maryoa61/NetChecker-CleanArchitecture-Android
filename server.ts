import express from "express";
import path from "path";
import net from "net";
import fs from "fs";
import JSZip from "jszip";
import { createServer as createViteServer } from "vite";

async function startServer() {
  const app = express();
  const PORT = 3000;

  app.use(express.json());

  // API Route: Health ping
  app.get("/api/health", (req, res) => {
    res.json({ status: "ok", timestamp: new Date().toISOString() });
  });

  // API Route: True-HTTP / TCP Ping Health Check
  // Measure exact response time on port 443 with custom timeout
  app.post("/api/ping", (req, res) => {
    const { host, port = 443, timeout = 1000 } = req.body;
    if (!host) {
      return res.status(400).json({ error: "Host parameter is required" });
    }

    const start = Date.now();
    const socket = new net.Socket();
    
    socket.setTimeout(timeout);

    const finish = (success: boolean, errorMsg?: string) => {
      socket.destroy();
      const elapsed = Date.now() - start;
      res.json({
        host,
        port,
        success,
        pingMs: success ? elapsed : -1,
        error: errorMsg || null,
        timestamp: Date.now()
      });
    };

    socket.connect(port, host, () => {
      finish(true);
    });

    socket.on("error", (err) => {
      finish(false, err.message);
    });

    socket.on("timeout", () => {
      finish(false, "Connection timed out");
    });
  });

  // Helper inside server to probe IP latency via TCP connects
  function checkIpTCP(ip: string, port: number = 443, timeout: number = 1000): Promise<{ success: boolean; timeMs: number }> {
    return new Promise((resolve) => {
      const start = Date.now();
      const socket = new net.Socket();
      
      socket.setTimeout(timeout);
      
      socket.connect(port, ip, () => {
        const timeMs = Date.now() - start;
        socket.destroy();
        resolve({ success: true, timeMs });
      });
      
      const fail = () => {
        socket.destroy();
        resolve({ success: false, timeMs: -1 });
      };

      socket.on("error", fail);
      socket.on("timeout", fail);
    });
  }

  // API Route: Live-Streaming Scanner (SSE)
  // Utilizes a dynamic workers semaphore/queue pattern
  app.get("/api/scan/stream", async (req, res) => {
    res.setHeader("Content-Type", "text/event-stream");
    res.setHeader("Cache-Control", "no-cache");
    res.setHeader("Connection", "keep-alive");

    const ipParam = req.query.ips as string;
    let ips: string[] = [];

    if (ipParam) {
      ips = ipParam.split(",").map(s => s.trim()).filter(Boolean);
    } else {
      // Generate standard target Cloudflare IP ranges representation
      // Samples taken across various Cloudflare standard CIDR subnets
      const subnets = [
        "104.16.20", "104.17.44", "104.18.15", "172.64.32", 
        "172.67.68", "162.159.2", "108.162.194", "188.114.97"
      ];
      const count = 40;
      for (let i = 0; i < count; i++) {
        const subnet = subnets[i % subnets.length];
        const host = Math.floor(Math.random() * 254) + 1;
        ips.push(`${subnet}.${host}`);
      }
    }

    const concurrency = Math.min(parseInt(req.query.concurrency as string) || 50, 80);
    const timeout = Math.min(parseInt(req.query.timeout as string) || 1000, 5000);

    // Write initial scan metadata header
    res.write(`data: ${JSON.stringify({ type: "start", total: ips.length, concurrency, timeout })}\n\n`);

    let nextIndex = 0;

    // Concurrent Worker instances
    const runWorker = async () => {
      while (nextIndex < ips.length) {
        const index = nextIndex++;
        const ip = ips[index];
        
        // Measure real TCP round trip time on 443
        const result = await checkIpTCP(ip, 443, timeout);
        
        if (res.writableEnded) break;

        res.write(`data: ${JSON.stringify({
          type: "result",
          index,
          ip,
          success: result.success,
          pingMs: result.timeMs
        })}\n\n`);
      }
    };

    // Spawn concurrent checkers
    const workers = Array.from({ length: Math.min(concurrency, ips.length) }, runWorker);
    await Promise.all(workers);

    if (!res.writableEnded) {
      res.write(`data: ${JSON.stringify({ type: "complete" })}\n\n`);
      res.end();
    }
  });

  // Helper inside server to recursively add a directory to a JSZip instance
  function addDirToZip(zipInstance: JSZip, localPath: string, zipPathPrefix: string = "") {
    const files = fs.readdirSync(localPath);
    for (const file of files) {
      const filePath = path.join(localPath, file);
      const stat = fs.statSync(filePath);
      const zipPath = zipPathPrefix ? `${zipPathPrefix}/${file}` : file;
      if (stat.isDirectory()) {
        addDirToZip(zipInstance, filePath, zipPath);
      } else {
        const content = fs.readFileSync(filePath);
        zipInstance.file(zipPath, content);
      }
    }
  }

  // API Route: Build-Ready Android Project Export ZIP (packaged live from server disk)
  app.get("/api/export-android", async (req, res) => {
    try {
      const zip = new JSZip();
      
      // Top-level custom files to help guide the developer
      zip.file("README.md", `# NetChecker Cloudflare Android Client\n\nThis is a production-ready Native Android app structured in clear Clean Architecture implementing a multi-threaded Cloudflare IP scanner on port 443 with Live Jetpack Compose UI.\n\n## Build Prerequisites\n- Android Studio Koala / Ladybug or newer\n- JDK 17 (pre-configured in modern Android Studio gradle toolchain)`);
      
      const androidDir = path.join(process.cwd(), "android");
      if (fs.existsSync(androidDir)) {
        addDirToZip(zip, androidDir, "NetChecker");
      } else {
        return res.status(404).json({ error: "Android source directory not found on server disk" });
      }
      
      const buffer = await zip.generateAsync({ type: "nodebuffer" });
      res.setHeader("Content-Type", "application/zip");
      res.setHeader("Content-Disposition", 'attachment; filename="NetChecker-CleanArchitecture-Android.zip"');
      res.send(buffer);
    } catch (err: any) {
      console.error("Failed to generate and stream zip from disk", err);
      res.status(500).send("Zipping failed on server: " + err.message);
    }
  });

  // Vite Integration: Express serves Vite dev middleware or static compiled files
  if (process.env.NODE_ENV !== "production") {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), "dist");
    app.use(express.static(distPath));
    app.get("*", (req, res) => {
      res.sendFile(path.join(distPath, "index.html"));
    });
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server successfully started on http://0.0.0.0:${PORT}`);
  });
}

startServer().catch((err) => {
  console.error("Critical server bootstrap error:", err);
});
