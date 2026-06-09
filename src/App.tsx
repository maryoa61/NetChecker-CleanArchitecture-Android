import React, { useState, useEffect, useRef } from "react";
import JSZip from "jszip";
import * as d3 from "d3";
import { 
  Play, 
  Square, 
  Trash2, 
  Plus, 
  Database, 
  Network, 
  Code, 
  Download, 
  RefreshCw, 
  Wifi, 
  Layers, 
  Smartphone, 
  CheckCircle2, 
  XCircle, 
  Copy, 
  ExternalLink,
  ChevronRight,
  Shield,
  Clock,
  Zap,
  Gauge
} from "lucide-react";
import { motion, AnimatePresence } from "motion/react";
import { ANDROID_PROJECT_FILES, AndroidFile } from "./androidTemplates";

// Local storage keys for simulated Room DB state
const LOCAL_IPS_KEY = "netchecker_simulated_ips";
const LOCAL_PROXIES_KEY = "netchecker_simulated_proxies";

interface SimulatedIp {
  ipAddress: string;
  pingMs: number;
  lastChecked: number;
}

interface SimulatedProxy {
  id: string;
  remarks: string;
  rawConfig: string;
  currentPing: number;
  isWorking: boolean;
  pingHistory?: { time: string; ping: number }[];
  throughputResult?: {
    speedMbps: number;
    packetLoss: number;
    testTime: string;
  };
}

interface ProxyPingChartProps {
  history: { time: string; ping: number }[];
}

function ProxyPingChart({ history }: ProxyPingChartProps) {
  const svgRef = useRef<SVGSVGElement | null>(null);

  useEffect(() => {
    if (!svgRef.current) return;

    const validData = (history || []).filter(d => d.ping > 0);
    if (!history || history.length === 0 || validData.length === 0) {
      // Draw empty placeholder or reset SVG
      const svg = d3.select(svgRef.current);
      svg.selectAll("*").remove();
      return;
    }

    const margin = { top: 12, right: 12, bottom: 18, left: 24 };
    const width = 320;
    const height = 90;

    const svg = d3.select(svgRef.current);
    svg.selectAll("*").remove();

    svg
      .attr("viewBox", `0 0 ${width} ${height}`)
      .attr("width", "100%")
      .attr("height", "100%");

    // X Scale: Point scale mapping time labels
    const xScale = d3.scalePoint()
      .domain(history.map(d => d.time))
      .range([margin.left, width - margin.right]);

    // Y Scale: Linear mapping of ping latencies
    const maxPing = d3.max(validData, d => d.ping) || 100;
    const minY = 0;
    const maxY = Math.ceil((maxPing * 1.15) / 10) * 10;

    const yScale = d3.scaleLinear()
      .domain([minY, maxY])
      .range([height - margin.bottom, margin.top]);

    // Y Gridlines
    const yTicksCount = 3;
    const gridTicks = d3.ticks(minY, maxY, yTicksCount);
    gridTicks.forEach(val => {
      if (val === minY) return;
      svg.append("line")
        .attr("x1", margin.left)
        .attr("y1", yScale(val))
        .attr("x2", width - margin.right)
        .attr("y2", yScale(val))
        .attr("stroke", "#ff00ff")
        .attr("stroke-opacity", 0.08)
        .attr("stroke-width", 0.8)
        .attr("stroke-dasharray", "1,2");
    });

    // Gradients
    const defs = svg.append("defs");
    const areaGrad = defs.append("linearGradient")
      .attr("id", "blue-area-grad")
      .attr("x1", "0%").attr("y1", "0%")
      .attr("x2", "0%").attr("y2", "100%");

    areaGrad.append("stop")
      .attr("offset", "0%")
      .attr("stop-color", "#00f2ff")
      .attr("stop-opacity", 0.22);
    areaGrad.append("stop")
      .attr("offset", "100%")
      .attr("stop-color", "#00f2ff")
      .attr("stop-opacity", 0.0);

    // Filter for glowing aura
    const glowF = defs.append("filter")
      .attr("id", "neon-line-glow")
      .attr("x", "-20%")
      .attr("y", "-20%")
      .attr("width", "140%")
      .attr("height", "140%");

    glowF.append("feGaussianBlur")
      .attr("stdDeviation", "1.5")
      .attr("result", "blur");

    glowF.append("feMerge")
      .selectAll("feMergeNode")
      .data(["blur", "SourceGraphic"])
      .enter()
      .append("feMergeNode")
      .attr("in", d => d);

    // Area path under line
    const areaGenerator = d3.area<{ time: string; ping: number }>()
      .x(d => xScale(d.time)!)
      .y0(height - margin.bottom)
      .y1(d => yScale(d.ping))
      .defined(d => d.ping > 0);

    svg.append("path")
      .datum(history)
      .attr("d", areaGenerator)
      .attr("fill", "url(#blue-area-grad)");

    // Main line path
    const lineGenerator = d3.line<{ time: string; ping: number }>()
      .x(d => xScale(d.time)!)
      .y(d => yScale(d.ping))
      .defined(d => d.ping > 0)
      .curve(d3.curveMonotoneX);

    svg.append("path")
      .datum(history)
      .attr("d", lineGenerator)
      .attr("fill", "none")
      .attr("stroke", "#00f2ff")
      .attr("stroke-width", 1.5)
      .attr("filter", "url(#neon-line-glow)");

    // X Axis Setup
    const sampleSize = history.length;
    const showIndices = [0, Math.floor(sampleSize / 2), sampleSize - 1].filter(i => i >= 0 && i < sampleSize);
    const uniqueIndices = Array.from(new Set(showIndices));
    const targetTicks = uniqueIndices.map(idx => history[idx].time);

    const xAxis = d3.axisBottom(xScale)
      .tickValues(targetTicks)
      .tickSize(2)
      .tickFormat(d => d);

    svg.append("g")
      .attr("transform", `translate(0, ${height - margin.bottom})`)
      .call(xAxis)
      .call(g => g.select(".domain").attr("stroke", "#ff00ff").attr("stroke-opacity", 0.15))
      .call(g => g.selectAll(".tick line").attr("stroke", "#ff00ff").attr("stroke-opacity", 0.15))
      .call(g => g.selectAll(".tick text")
        .attr("fill", "#6f7f9f")
        .attr("font-size", "6px")
        .attr("font-family", "monospace")
      );

    // Y Axis Setup
    const yAxis = d3.axisLeft(yScale)
      .ticks(3)
      .tickSize(2)
      .tickFormat(d => `${d}ms`);

    svg.append("g")
      .attr("transform", `translate(${margin.left}, 0)`)
      .call(yAxis)
      .call(g => g.select(".domain").attr("stroke", "#ff00ff").attr("stroke-opacity", 0.15))
      .call(g => g.selectAll(".tick line").attr("stroke", "#ff00ff").attr("stroke-opacity", 0.15))
      .call(g => g.selectAll(".tick text")
        .attr("fill", "#6f7f9f")
        .attr("font-size", "6px")
        .attr("font-family", "monospace")
      );

    // Data dots
    history.forEach((d) => {
      if (d.ping <= 0) return;
      const cx = xScale(d.time);
      const cy = yScale(d.ping);
      if (cx !== undefined && cy !== undefined) {
        svg.append("circle")
          .attr("cx", cx)
          .attr("cy", cy)
          .attr("r", 2.2)
          .attr("fill", "#ff00ff")
          .attr("opacity", 0.5)
          .attr("filter", "url(#neon-line-glow)");

        svg.append("circle")
          .attr("cx", cx)
          .attr("cy", cy)
          .attr("r", 1)
          .attr("fill", "#ffffff");
      }
    });

  }, [history]);

  const validCount = (history || []).filter(d => d.ping > 0).length;

  return (
    <div className="w-full bg-[#030306] border border-[#ff00ff]/20 p-1 relative select-none">
      {(!history || history.length === 0 || validCount === 0) ? (
        <div className="w-full h-[76px] flex items-center justify-center text-[7px] text-gray-600 font-mono text-center">
          WAITING FOR GATEWAY TELEMETRY PINGS
        </div>
      ) : (
        <div className="relative">
          <div className="absolute top-0 right-1 text-[5.5px] text-zinc-600 font-mono tracking-widest">
            SCOPE ANALYZER v2.1
          </div>
          <svg ref={svgRef} className="w-full h-[76px]" />
        </div>
      )}
    </div>
  );
}

export default function App() {
  // Mobile UI Tabs inside phone simulator
  const [activePhoneTab, setActivePhoneTab] = useState<"SCANNER" | "PROXIES">("SCANNER");

  // React State - Scanner Engine
  const [isScanning, setIsScanning] = useState(false);
  const [currentIp, setCurrentIp] = useState("IDLE");
  const [checkedCount, setCheckedCount] = useState(0);
  const [totalCount, setTotalCount] = useState(0);
  const [scanProgress, setScanProgress] = useState(0);
  const [scannedResults, setScannedResults] = useState<{ ip: string; success: boolean; pingMs: number }[]>([]);

  // Filtering & Sorting State
  const [selectedOperator, setSelectedOperator] = useState<string>("ALL");
  const [sortIpsByPing, setSortIpsByPing] = useState<boolean>(false);
  const [sortProxiesByPing, setSortProxiesByPing] = useState<boolean>(false);
  const [filterWorkingProxies, setFilterWorkingProxies] = useState<boolean>(false);

  // Batch Testing States
  const [isTestingProxies, setIsTestingProxies] = useState<boolean>(false);
  const [proxiesTestedCount, setProxiesTestedCount] = useState<number>(0);
  const [proxiesTotalCount, setProxiesTotalCount] = useState<number>(0);

  // Simulated Databases (Saved to state + LocalStorage)
  const [savedIps, setSavedIps] = useState<SimulatedIp[]>([]);
  const [savedProxies, setSavedProxies] = useState<SimulatedProxy[]>([]);
  const [recentlyCopiedId, setRecentlyCopiedId] = useState<string | null>(null);
  const [selectedProxyId, setSelectedProxyId] = useState<string | null>(null);

  // Simulated Throughput States
  const [testingThroughputId, setTestingThroughputId] = useState<string | null>(null);
  const [throughputProgress, setThroughputProgress] = useState<number>(0);
  const [throughputPhase, setThroughputPhase] = useState<string>("");

  // Inputs for Adding Proxy inside Simulator
  const [remarksInput, setRemarksInput] = useState("");
  const [configInput, setConfigInput] = useState("");
  const [isAddingProxy, setIsAddingProxy] = useState(false);

  // Export Client States
  const [exportFormat, setExportFormat] = useState<"V2RAY" | "CLASH" | "SUBSCRIPTION">("V2RAY");
  const [exportFilterLowLoss, setExportFilterLowLoss] = useState(false);
  const [exportFilterLowPing, setExportFilterLowPing] = useState(false);
  const [showExportModal, setShowExportModal] = useState(false);
  const [copiedExport, setCopiedExport] = useState(false);

  // Developer Studio States
  const [selectedFileIndex, setSelectedFileIndex] = useState(2); // Default to HttpClient.kt
  const [copiedFile, setCopiedFile] = useState(false);
  const [exportingZip, setExportingZip] = useState(false);

  // References for streaming API
  const eventSourceRef = useRef<EventSource | null>(null);

  // Initialize and load saved storage data
  useEffect(() => {
    const rawIps = localStorage.getItem(LOCAL_IPS_KEY);
    const rawProxies = localStorage.getItem(LOCAL_PROXIES_KEY);

    if (rawIps) {
      setSavedIps(JSON.parse(rawIps));
    } else {
      // Seed some default Clean IPs
      const seedIps = [
        { ipAddress: "104.16.44.12", pingMs: 45, lastChecked: Date.now(), operatorType: "MCI" },
        { ipAddress: "172.64.1.98", pingMs: 58, lastChecked: Date.now(), operatorType: "Irancell" },
        { ipAddress: "108.162.194.5", pingMs: 82, lastChecked: Date.now(), operatorType: "Wi-Fi" }
      ];
      setSavedIps(seedIps);
      localStorage.setItem(LOCAL_IPS_KEY, JSON.stringify(seedIps));
    }

    if (rawProxies) {
      let parsed = JSON.parse(rawProxies);
      parsed = parsed.map((p: any) => {
        if (!p.pingHistory || p.pingHistory.length === 0) {
          const mockHistory = [];
          const basePing = p.currentPing > 0 ? p.currentPing : 65;
          const count = 8;
          const nowTime = Date.now();
          for (let i = count; i >= 1; i--) {
            const timeDiff = i * 2 * 60 * 1000;
            const dateObj = new Date(nowTime - timeDiff);
            const timeStr = dateObj.toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
            const variance = Math.floor(Math.random() * 20) - 10;
            const pingVal = Math.max(15, basePing + variance);
            mockHistory.push({ time: timeStr, ping: p.currentPing > 0 || Math.random() > 0.3 ? pingVal : -1 });
          }
          return { ...p, pingHistory: mockHistory };
        }
        return p;
      });
      setSavedProxies(parsed);
      if (parsed.length > 0) {
        setSelectedProxyId(parsed[0].id);
      }
    } else {
      // Seed some proxy presets
      const seedProxies = [
        {
          id: "1",
          remarks: "Cloudflare Warp Fronting",
          rawConfig: "vless://cf-secure-bypass-node@172.64.0.1:443?encryption=none&security=tls",
          currentPing: 45,
          isWorking: true,
          pingHistory: [
            { time: "09:50:00", ping: 53 },
            { time: "09:52:00", ping: 48 },
            { time: "09:54:00", ping: 62 },
            { time: "09:56:00", ping: 44 },
            { time: "09:58:00", ping: 49 },
            { time: "10:00:00", ping: 45 }
          ]
        },
        {
          id: "2",
          remarks: "Xray SNI Spoof Gateway",
          rawConfig: "trojan://telecom-unblock-node@104.18.15.54:443?sni=speedtest.net",
          currentPing: -1,
          isWorking: false,
          pingHistory: [
            { time: "09:50:00", ping: -1 },
            { time: "09:52:00", ping: -1 },
            { time: "09:54:00", ping: -1 }
          ]
        }
      ];
      setSavedProxies(seedProxies);
      localStorage.setItem(LOCAL_PROXIES_KEY, JSON.stringify(seedProxies));
      setSelectedProxyId("1");
    }
  }, []);

  // Save changes to local persistence
  const updateSavedIps = (newIps: SimulatedIp[]) => {
    setSavedIps(newIps);
    localStorage.setItem(LOCAL_IPS_KEY, JSON.stringify(newIps));
  };

  const updateSavedProxies = (newProxies: SimulatedProxy[]) => {
    setSavedProxies(newProxies);
    localStorage.setItem(LOCAL_PROXIES_KEY, JSON.stringify(newProxies));
  };

  // Handle Cloudflare multi-threaded IP scanner live check (SSE)
  const handleStartScan = () => {
    if (isScanning) return;

    setIsScanning(true);
    setCheckedCount(0);
    setTotalCount(0);
    setScanProgress(0);
    setScannedResults([]);

    // Open real SSE Channel on Port 3000 custom route with Semaphore high-performance 50 concurrent lookups
    const eventSource = new EventSource("/api/scan/stream?concurrency=50&timeout=850");
    eventSourceRef.current = eventSource;

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);

        if (data.type === "start") {
          setTotalCount(data.total);
        } else if (data.type === "result") {
          setCheckedCount(data.index + 1);
          setScanProgress(((data.index + 1) / totalCount) || 0);
          setCurrentIp(data.ip);

          // Append to live results container
          setScannedResults((prev) => [
            { ip: data.ip, success: data.success, pingMs: data.pingMs },
            ...prev
          ]);

          // If IP was cleaned successfully, save to our simulated Room database flow
          if (data.success) {
            setSavedIps((prev) => {
              const exists = prev.some((x) => x.ipAddress === data.ip);
              if (exists) return prev;
              const operators = ["MCI", "Irancell", "Wi-Fi"];
              let hashCode = 0;
              for (let i = 0; i < data.ip.length; i++) {
                hashCode = data.ip.charCodeAt(i) + ((hashCode << 5) - hashCode);
              }
              const opIndex = Math.abs(hashCode) % operators.length;
              const assignedOperator = operators[opIndex];
              const fresh = [{ ipAddress: data.ip, pingMs: data.pingMs, lastChecked: Date.now(), operatorType: assignedOperator }, ...prev];
              localStorage.setItem(LOCAL_IPS_KEY, JSON.stringify(fresh));
              return fresh;
            });
          }
        } else if (data.type === "complete") {
          setIsScanning(false);
          setCurrentIp("SCAN COMPLETE");
          eventSource.close();
        }
      } catch (err) {
        console.error("SSE parse failure", err);
      }
    };

    eventSource.onerror = (err) => {
      console.error("SSE Connection error", err);
      setIsScanning(false);
      setCurrentIp("SCAN DISRUPTED");
      eventSource.close();
    };
  };

  const handleStopScan = () => {
    if (eventSourceRef.current) {
      eventSourceRef.current.close();
    }
    setIsScanning(false);
    setCurrentIp("SCAN ABORTED");
  };

  // Delete IP from simulated Room
  const handleDeleteIp = (ipAddr: string) => {
    const updated = savedIps.filter(x => x.ipAddress !== ipAddr);
    updateSavedIps(updated);
  };

  const handleClearAllIps = () => {
    updateSavedIps([]);
  };

  const handleExportIpsJson = () => {
    if (savedIps.length === 0) return;
    const jsonString = `data:text/json;charset=utf-8,${encodeURIComponent(
      JSON.stringify(savedIps, null, 2)
    )}`;
    const downloadAnchor = document.createElement("a");
    downloadAnchor.setAttribute("href", jsonString);
    downloadAnchor.setAttribute("download", `verified_ips_backup_${Date.now()}.json`);
    document.body.appendChild(downloadAnchor);
    downloadAnchor.click();
    downloadAnchor.remove();
  };

  // Add Custom Proxy Config with native-like parser & link generator optimization
  const handleAddProxy = (e: React.FormEvent) => {
    e.preventDefault();
    if (!configInput) return;

    let finalRemarks = remarksInput || "Optimized-Gate";
    let finalConfig = configInput;

    const trimmed = configInput.trim();
    const isUri = trimmed.startsWith("vless://") || trimmed.startsWith("vmess://") || trimmed.startsWith("trojan://");

    if (isUri) {
      // Find fastest IP from scanned results
      const fastestIp = scannedResults.length > 0 
        ? [...scannedResults].sort((a,b) => (a.pingMs || 9999) - (b.pingMs || 9999))[0]?.ip 
        : savedIps.length > 0 
        ? [...savedIps].sort((a,b) => a.pingMs - b.pingMs)[0]?.ipAddress 
        : "104.16.1.1";

      if (trimmed.startsWith("vless://") || trimmed.startsWith("trojan://")) {
        try {
          const parts = trimmed.split("#");
          const uriAndQuery = parts[0];
          const rawRemarks = parts[1] || "Gateway";
          
          const protocol = trimmed.startsWith("vless://") ? "vless://" : "trojan://";
          const queryParts = uriAndQuery.replace(protocol, "").split("?");
          
          const authAndHost = queryParts[0];
          const queryString = queryParts[1] || "";
          
          const lastAt = authAndHost.lastIndexOf("@");
          const auth = authAndHost.substring(0, lastAt);
          const hostAndPort = authAndHost.substring(lastAt + 1);
          
          const hostPortSplit = hostAndPort.split(":");
          const originalHost = hostPortSplit[0];
          const parsedPort = hostPortSplit[1] || "443";
          
          // Keep dynamic routing targets matching origin domain
          const queryParams = new URLSearchParams(queryString);
          if (!queryParams.get("sni")) queryParams.set("sni", originalHost);
          if (!queryParams.get("host")) queryParams.set("host", originalHost);
          
          const name = decodeURIComponent(rawRemarks);
          finalRemarks = name.endsWith("-Optimized") ? name : `${name}-Optimized`;
          
          finalConfig = `${protocol}${auth}@${fastestIp}:${parsedPort}?${queryParams.toString()}#${encodeURIComponent(finalRemarks)}`;
        } catch (err) {
          console.error(err);
        }
      } else if (trimmed.startsWith("vmess://")) {
        try {
          const b64 = trimmed.substring(8);
          const decoded = atob(b64);
          const json = JSON.parse(decoded);
          const originalHost = json.add;
          const originalRemarks = json.ps || "Gate";
          
          json.add = fastestIp;
          if (!json.host) json.host = originalHost;
          if (!json.sni) json.sni = originalHost;
          
          finalRemarks = originalRemarks.endsWith("-Optimized") ? originalRemarks : `${originalRemarks}-Optimized`;
          json.ps = finalRemarks;
          
          const reencoded = btoa(JSON.stringify(json));
          finalConfig = `vmess://${reencoded}`;
        } catch (err) {
          console.error(err);
        }
      }
    }

    const fresh: SimulatedProxy = {
      id: Date.now().toString(),
      remarks: finalRemarks,
      rawConfig: finalConfig,
      currentPing: -1,
      isWorking: false
    };

    updateSavedProxies([fresh, ...savedProxies]);
    setRemarksInput("");
    setConfigInput("");
    setIsAddingProxy(false);
  };

  // Delete Custom Proxy Config
  const handleDeleteProxy = (id: string) => {
    const updated = savedProxies.filter(x => x.id !== id);
    updateSavedProxies(updated);
  };

  // Dynamically compute the exported text and formatted name
  const { exportModalFormat, exportModalText } = React.useMemo(() => {
    let modeFormatName = "V2Ray Outbounds JSON";
    if (exportFormat === "CLASH") {
      modeFormatName = "Clash Proxies config";
    } else if (exportFormat === "SUBSCRIPTION") {
      modeFormatName = "Base64 Subscription Link";
    }

    let working = savedProxies.filter(p => p.isWorking);
    if (exportFilterLowLoss) {
      working = working.filter(p => {
        const loss = p.throughputResult?.packetLoss ?? 0;
        return loss < 1;
      });
    }
    if (exportFilterLowPing) {
      working = working.filter(p => p.currentPing > 0 && p.currentPing < 100);
    }

    if (working.length === 0) {
      return {
        exportModalFormat: modeFormatName,
        exportModalText: "// No working proxies match the selected filters (Loss < 1% or Ping < 100ms)."
      };
    }

    let finalStr = "";
    if (exportFormat === "V2RAY") {
      const outbounds = working.map((proxy) => {
        const raw = proxy.rawConfig.trim();
        let protocol = "vless";
        let address = "104.16.1.1";
        let port = 443;
        let uuid = "uuid-placeholder";
        let tls = null;
        let sni = null;
        let encryption = "none";

        try {
          if (raw.startsWith("vless://") || raw.startsWith("trojan://")) {
            protocol = raw.startsWith("vless://") ? "vless" : "trojan";
            const cleanUri = raw.split("#")[0];
            const atIndex = cleanUri.indexOf("@");
            if (atIndex !== -1) {
              uuid = cleanUri.substring(protocol.length + 3, atIndex);
              const rest = cleanUri.substring(atIndex + 1);
              const queryMark = rest.indexOf("?");
              const hostPort = queryMark !== -1 ? rest.substring(0, queryMark) : rest;
              const queryString = queryMark !== -1 ? rest.substring(queryMark + 1) : "";

              const colon = hostPort.indexOf(":");
              address = colon !== -1 ? hostPort.substring(0, colon) : hostPort;
              port = colon !== -1 ? parseInt(hostPort.substring(colon + 1)) || 443 : 443;

              if (queryString) {
                const params = new URLSearchParams(queryString);
                if (params.get("sni")) sni = params.get("sni");
                if (params.get("encryption")) encryption = params.get("encryption");
                if (params.get("security") === "tls") tls = "tls";
              }
            }
          } else if (raw.startsWith("vmess://")) {
            protocol = "vmess";
            const b64 = raw.substring(8).trim();
            const decoded = atob(b64);
            const json = JSON.parse(decoded);
            address = json.add || "104.16.1.1";
            port = parseInt(json.port) || 443;
            uuid = json.id || "";
            tls = json.tls === "tls" ? "tls" : null;
            sni = json.sni || null;
          }
        } catch (e) {
          console.error(e);
        }

        const outbound: any = {
          tag: proxy.remarks,
          protocol: protocol,
          settings: {
            vnext: [
              {
                address: address,
                port: port,
                users: [
                  {
                    id: uuid,
                    encryption: encryption
                  }
                ]
              }
            ]
          },
          streamSettings: {
            network: "tcp"
          }
        };

        if (tls === "tls") {
          outbound.streamSettings.security = "tls";
          outbound.streamSettings.tlsSettings = {
            serverName: sni || address
          };
        }

        return outbound;
      });

      finalStr = JSON.stringify({ outbounds }, null, 2);
    } else if (exportFormat === "CLASH") {
      const proxies = working.map((proxy) => {
        const raw = proxy.rawConfig.trim();
        let protocol = "vless";
        let address = "104.16.1.1";
        let port = 443;
        let uuid = "uuid-placeholder";
        let tls = false;
        let sni = null;
        let host = null;

        try {
          if (raw.startsWith("vless://") || raw.startsWith("trojan://")) {
            protocol = raw.startsWith("vless://") ? "vless" : "trojan";
            const cleanUri = raw.split("#")[0];
            const atIndex = cleanUri.indexOf("@");
            if (atIndex !== -1) {
              uuid = cleanUri.substring(protocol.length + 3, atIndex);
              const rest = cleanUri.substring(atIndex + 1);
              const queryMark = rest.indexOf("?");
              const hostPort = queryMark !== -1 ? rest.substring(0, queryMark) : rest;
              const queryString = queryMark !== -1 ? rest.substring(queryMark + 1) : "";

              const colon = hostPort.indexOf(":");
              address = colon !== -1 ? hostPort.substring(0, colon) : hostPort;
              port = colon !== -1 ? parseInt(hostPort.substring(colon + 1)) || 443 : 443;

              if (queryString) {
                const params = new URLSearchParams(queryString);
                if (params.get("sni")) sni = params.get("sni");
                if (params.get("host")) host = params.get("host");
                if (params.get("security") === "tls") tls = true;
              }
            }
          } else if (raw.startsWith("vmess://")) {
            protocol = "vmess";
            const b64 = raw.substring(8).trim();
            const decoded = atob(b64);
            const json = JSON.parse(decoded);
            address = json.add || "104.16.1.1";
            port = parseInt(json.port) || 443;
            uuid = json.id || "";
            tls = json.tls === "tls";
            sni = json.sni || null;
            host = json.host || null;
          }
        } catch (e) {
          console.error(e);
        }

        const item: any = {
          name: proxy.remarks,
          type: protocol,
          server: address,
          port: port,
          uuid: uuid,
          udp: true,
          tls: tls
        };

        if (sni) item.sni = sni;
        if (host) item.host = host;

        return item;
      });

      finalStr = JSON.stringify({ proxies }, null, 2);
    } else if (exportFormat === "SUBSCRIPTION") {
      const list = working.map((p) => p.rawConfig.trim()).join("\n");
      try {
        finalStr = btoa(list);
      } catch (e) {
        finalStr = list;
        modeFormatName = "Plain URI List Subscription";
      }
    }

    return {
      exportModalFormat: modeFormatName,
      exportModalText: finalStr
    };
  }, [showExportModal, savedProxies, exportFormat, exportFilterLowLoss, exportFilterLowPing]);

  // Helper to format/export 'Working' proxy configs into various formats
  const handleExportWorkingProxies = (format: "V2RAY" | "CLASH" | "SUBSCRIPTION") => {
    const working = savedProxies.filter(p => p.isWorking);
    if (working.length === 0) {
      alert("No working proxies detected. Please run 'TEST ALL CONFIGS' or ping nodes to identify live gateways first.");
      return;
    }
    setExportFilterLowLoss(false);
    setExportFilterLowPing(false);
    setExportFormat(format);
    setShowExportModal(true);
  };

  // Concurrent batch proxy testing mimicking Kotlin Coroutines / Semaphore UseCase
  const handleBatchTestProxies = async () => {
    if (isTestingProxies || savedProxies.length === 0) return;

    setIsTestingProxies(true);
    setProxiesTotalCount(savedProxies.length);
    setProxiesTestedCount(0);

    // Set all proxies to loading state (0 means checking...)
    let currentData = savedProxies.map(p => ({ ...p, currentPing: 0 }));
    setSavedProxies(currentData);

    const maxConcurrency = 5; // limit concurrency in browser pool
    const copies = [...savedProxies];

    // Worker queue
    let activePointer = 0;

    const worker = async () => {
      while (activePointer < copies.length) {
        const index = activePointer++;
        if (index >= copies.length) break;

        const proxy = copies[index];
        try {
          const response = await fetch("/api/ping", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ host: "1.1.1.1", timeout: 1000 })
          });
          const data = await response.json();

          currentData = currentData.map(p => {
            if (p.id === proxy.id) {
              const nowStr = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
              const pingM = data.success ? data.pingMs : -1;
              const newHistory = [...(p.pingHistory || [])];
              newHistory.push({ time: nowStr, ping: pingM });
              if (newHistory.length > 15) {
                newHistory.shift();
              }
              return {
                ...p,
                currentPing: pingM,
                isWorking: data.success,
                pingHistory: newHistory
              };
            }
            return p;
          });
        } catch (e) {
          currentData = currentData.map(p => {
            if (p.id === proxy.id) {
              const nowStr = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
              const newHistory = [...(p.pingHistory || [])];
              newHistory.push({ time: nowStr, ping: -1 });
              if (newHistory.length > 15) {
                newHistory.shift();
              }
              return {
                ...p,
                currentPing: -1,
                isWorking: false,
                pingHistory: newHistory
              };
            }
            return p;
          });
        }

        setSavedProxies([...currentData]);
        setProxiesTestedCount(prev => prev + 1);
      }
    };

    const workers = Array.from({ length: Math.min(maxConcurrency, copies.length) }, worker);
    await Promise.all(workers);

    localStorage.setItem(LOCAL_PROXIES_KEY, JSON.stringify(currentData));
    setIsTestingProxies(false);
  };

  // Trigger server-side HTTP/HEAD ping request for proxy simulation
  const handleTestProxyPing = async (proxy: SimulatedProxy) => {
    // Show a rapid scanning state (currentPing is 0 representing loading)
    const staging = savedProxies.map(p => p.id === proxy.id ? { ...p, currentPing: 0 } : p);
    setSavedProxies(staging);

    try {
      const response = await fetch("/api/ping", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        // Ping testing default reliable DNS / CDN nodes
        body: JSON.stringify({ host: "1.1.1.1", timeout: 1200 })
      });

      const data = await response.json();
      const updated = savedProxies.map(p => {
        if (p.id === proxy.id) {
          const nowStr = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
          const pingM = data.success ? data.pingMs : -1;
          const newHistory = [...(p.pingHistory || [])];
          newHistory.push({ time: nowStr, ping: pingM });
          if (newHistory.length > 15) {
            newHistory.shift();
          }
          return {
            ...p,
            currentPing: pingM,
            isWorking: data.success,
            pingHistory: newHistory
          };
        }
        return p;
      });
      updateSavedProxies(updated);
    } catch (e) {
      const updated = savedProxies.map(p => {
        if (p.id === proxy.id) {
          const nowStr = new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit', second: '2-digit' });
          const newHistory = [...(p.pingHistory || [])];
          newHistory.push({ time: nowStr, ping: -1 });
          if (newHistory.length > 15) {
            newHistory.shift();
          }
          return {
            ...p,
            currentPing: -1,
            isWorking: false,
            pingHistory: newHistory
          };
        }
        return p;
      });
      updateSavedProxies(updated);
    }
  };

  // Interactive Simulated Throughput Test based on Mathis' formula
  const handleRunThroughputTest = (proxy: SimulatedProxy) => {
    if (testingThroughputId) return; // limit concurrent test runs
    
    setTestingThroughputId(proxy.id);
    setThroughputProgress(0);
    setThroughputPhase("PROBING NETWORK PORT...");

    const phases = [
      { max: 15, text: "PROBING PORT 443..." },
      { max: 35, text: "SAMPLING PACKET RETRIES..." },
      { max: 55, text: "CALCULATING RTT FLUCTUATIONS..." },
      { max: 75, text: "RESOLVING TCP WINDOW SIZE..." },
      { max: 92, text: "ESTIMATING MATHIS RATIO..." },
      { max: 100, text: "CALCULATING MAXIMUM CAPACITY..." }
    ];

    let currentProg = 0;
    const interval = setInterval(() => {
      currentProg += Math.floor(Math.random() * 8) + 5;
      if (currentProg >= 100) {
        currentProg = 100;
        clearInterval(interval);

        const isOffline = !proxy.isWorking || proxy.currentPing <= 0;
        // Randomly sample packet-loss between 0.1% and 1.8%
        const lossRatePercent = isOffline ? 100.0 : Number((Math.random() * 1.7 + 0.1).toFixed(2));
        const currentRTT = proxy.currentPing > 0 ? proxy.currentPing : -1;
        
        let speedMbps = 0;
        if (!isOffline && currentRTT > 0) {
          const rttSec = currentRTT / 1000;
          const p = lossRatePercent / 100; // Fraction
          const mssBits = 1460 * 8; // bits
          // Mathis Formula: Rate = (MSS * C) / (RTT * sqrt(p))
          const maxBps = (mssBits * 1.22) / (rttSec * Math.sqrt(p));
          speedMbps = Number((maxBps / 1000000).toFixed(2));
        }

        const updated = savedProxies.map(p => {
          if (p.id === proxy.id) {
            return {
              ...p,
              throughputResult: {
                speedMbps,
                packetLoss: lossRatePercent,
                testTime: new Date().toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit' })
              }
            };
          }
          return p;
        });

        updateSavedProxies(updated);
        setTestingThroughputId(null);
      } else {
        setTestingThroughputId(proxy.id); // ensure it's pinned
        setThroughputProgress(currentProg);
        const match = phases.find(ph => currentProg <= ph.max);
        if (match) {
          setThroughputPhase(match.text);
        }
      }
    }, 100);
  };

  // Code Explorer helper
  const handleCopyCode = (text: string) => {
    navigator.clipboard.writeText(text);
    setCopiedFile(true);
    setTimeout(() => setCopiedFile(false), 2000);
  };

  // JSZip project directory compiler download
  const handleExportZipProject = async () => {
    setExportingZip(true);
    try {
      const response = await fetch("/api/export-android");
      if (!response.ok) {
        throw new Error("Server zip extraction returned status: " + response.status);
      }
      const blob = await response.blob();
      const downloadUrl = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = downloadUrl;
      anchor.download = "NetChecker-CleanArchitecture-Android.zip";
      document.body.appendChild(anchor);
      anchor.click();
      document.body.removeChild(anchor);
      URL.revokeObjectURL(downloadUrl);
    } catch (err) {
      console.error("ZIP dynamic construction on server failed, falling back to client-side packaging...", err);
      try {
        const zip = new JSZip();
        zip.file("README.md", `# NetChecker Cloudflare Android Client\n\nThis is a production-ready Native Android app structured in clear Clean Architecture implementing a multi-threaded Cloudflare IP scanner on port 443 with Live Jetpack Compose UI.`);
        
        ANDROID_PROJECT_FILES.forEach((file) => {
          zip.file(file.path, file.content);
        });

        const content = await zip.generateAsync({ type: "blob" });
        const downloadUrl = URL.createObjectURL(content);
        const anchor = document.createElement("a");
        anchor.href = downloadUrl;
        anchor.download = "NetChecker-CleanArchitecture-Android.zip";
        document.body.appendChild(anchor);
        anchor.click();
        document.body.removeChild(anchor);
        URL.revokeObjectURL(downloadUrl);
      } catch (clientErr) {
        console.error("Client fallback zipping failed:", clientErr);
      }
    } finally {
      setExportingZip(false);
    }
  };

  const selectedFile = ANDROID_PROJECT_FILES[selectedFileIndex];

  return (
    <div className="min-h-screen bg-[#050505] cyber-grid text-gray-200 font-sans selection:bg-[#00f2ff] selection:text-black relative">
      {/* Decorative background grid line accents */}
      <div className="absolute top-0 left-0 right-0 h-[2px] bg-gradient-to-r from-transparent via-[#00f2ff] to-[#ff00ff] opacity-80 z-50"></div>

      {/* GLITCH NEO HEADER */}
      <header className="border-b border-[#00f2ff]/20 bg-[#050505]/90 backdrop-blur-md px-6 py-5 sticky top-0 z-50">
        <div className="max-w-7xl mx-auto flex flex-col md:flex-row items-center justify-between gap-4">
          <div className="flex items-center gap-4">
            <div className="p-3 bg-gradient-to-br from-[#050505] to-[#0a0a0f] border border-[#00f2ff]/60 rounded-none text-[#00f2ff] shadow-[0_0_15px_rgba(0,242,255,0.25)] relative corner-accent">
              <Shield className="w-6 h-6 animate-pulse" />
            </div>
            <div>
              <div className="flex items-center gap-2">
                <span className="text-[10px] tracking-widest font-mono text-[#00f2ff] font-bold bg-[#050505] px-2.5 py-0.5 rounded-none border border-[#00f2ff]/40">
                  SYSTEM CORE v1.0
                </span>
                <span className="w-2.5 h-2.5 rounded-full bg-emerald-400 animate-ping"></span>
                <span className="text-[10px] text-emerald-400 font-mono tracking-wider">PORT 3000 ONLINE</span>
              </div>
              <h1 className="text-xl font-bold font-mono text-white tracking-widest uppercase mt-1.5 flex items-center gap-2">
                <span className="text-[#00f2ff]">&lt;</span>
                NETCHECKER // Android Clean Architecture Sandbox
                <span className="text-[#ff00ff]/70">&gt;</span>
              </h1>
            </div>
          </div>

          <div className="flex items-center gap-3">
            <button
              onClick={handleExportZipProject}
              disabled={exportingZip}
              id="export-zip-btn"
              className="group relative flex items-center gap-2 px-5 py-2.5 bg-[#050505] text-[#00f2ff] text-xs font-mono font-bold tracking-widest uppercase rounded-none border-2 border-[#00f2ff]/60 shadow-[0_0_15px_rgba(0,242,255,0.2)] hover:shadow-[0_0_25px_rgba(0,242,255,0.45)] hover:border-[#00f2ff] transition-all duration-300 cursor-pointer disabled:opacity-50"
            >
              {/* Corner mini accents for button */}
              <span className="absolute -top-1 -left-1 w-2 h-2 bg-[#00f2ff]"></span>
              <span className="absolute -bottom-1 -right-1 w-2 h-2 bg-[#ff00ff]"></span>
              
              {exportingZip ? (
                <>
                  <RefreshCw className="w-4 h-4 animate-spin text-[#00f2ff]" />
                  <span>Compiling SDK...</span>
                </>
              ) : (
                <>
                  <Download className="w-4 h-4 text-[#00f2ff] group-hover:translate-y-0.5 transition-transform" />
                  <span>Export Android Project (.zip)</span>
                </>
              )}
            </button>
          </div>
        </div>
      </header>

      {/* DASHBOARD SYSTEM HUBS */}
      <main className="max-w-7xl mx-auto p-4 md:p-6 grid grid-cols-1 lg:grid-cols-12 gap-6 pb-20">
        
        {/* COL 1: SYSTEM MONITOR PANEL (METRICS HUD) */}
        <section className="lg:col-span-12 grid grid-cols-1 sm:grid-cols-4 gap-4 p-5 border border-[#00f2ff]/15 bg-[#0a0a0f]/80 rounded-none relative">
          {/* Subtle frame accents */}
          <div className="absolute top-0 right-0 w-8 h-[1px] bg-[#00f2ff]"></div>
          <div className="absolute bottom-0 left-0 w-8 h-[1px] bg-[#ff00ff]"></div>

          <div className="p-4 bg-[#050505] border-l-4 border-l-[#ff00ff] border border-[#ff00ff]/20 rounded-none flex items-center gap-3.5 shadow-sm">
            <Smartphone className="text-[#ff00ff] w-5 h-5 flex-shrink-0" id="stat-phone-icon" />
            <div>
              <p className="text-[10px] text-gray-500 font-mono tracking-widest uppercase">SIMULATED PHONE</p>
              <h3 className="text-xs font-bold font-mono text-white tracking-widest uppercase mt-0.5">Pixel 8 // API 34</h3>
            </div>
          </div>

          <div className="p-4 bg-[#050505] border-l-4 border-l-[#00f2ff] border border-[#00f2ff]/20 rounded-none flex items-center justify-between gap-3.5 shadow-sm">
            <div className="flex items-center gap-3.5">
              <Database className="text-[#00f2ff] w-5 h-5 flex-shrink-0" id="stat-db-icon" />
              <div>
                <p className="text-[10px] text-gray-500 font-mono tracking-widest uppercase">ROOM DATABASE</p>
                <h3 className="text-xs font-bold font-mono text-white tracking-widest uppercase mt-0.5">{savedIps.length} IPS VERIFIED</h3>
              </div>
            </div>
            {savedIps.length > 0 && (
              <button
                onClick={handleExportIpsJson}
                className="px-2 py-1 text-[8.5px] font-mono border border-[#00f2ff]/40 bg-[#00f2ff]/10 hover:bg-[#00f2ff]/20 text-[#00f2ff] transition-all duration-150 rounded-none tracking-wider uppercase cursor-pointer"
                title="Backup Verified IP database as JSON"
              >
                [BACKUP JSON]
              </button>
            )}
          </div>

          <div className="p-4 bg-[#050505] border-l-4 border-l-[#00f2ff] border border-[#00f2ff]/20 rounded-none flex items-center gap-3.5 shadow-sm">
            <Zap className="text-[#00f2ff] w-5 h-5 flex-shrink-0" id="stat-latency-icon" />
            <div>
              <p className="text-[10px] text-gray-500 font-mono tracking-widest uppercase">AVERAGE LATENCY</p>
              <h3 className="text-xs font-bold font-mono text-[#00f2ff] tracking-widest uppercase mt-0.5">
                {savedIps.length > 0
                  ? `${Math.round(savedIps.reduce((acc, curr) => acc + curr.pingMs, 0) / savedIps.length)} ms`
                  : "N/A"}
              </h3>
            </div>
          </div>

          <div className="p-4 bg-[#050505] border-l-4 border-l-violet-500 border border-violet-500/20 rounded-none flex items-center gap-3.5 shadow-sm">
            <Network className="text-violet-400 w-5 h-5 flex-shrink-0" id="stat-proxy-icon" />
            <div>
              <p className="text-[10px] text-gray-500 font-mono tracking-widest uppercase">TUNNEL INJECTORS</p>
              <h3 className="text-xs font-bold font-mono text-white tracking-widest uppercase mt-0.5">{savedProxies.length} ACTIVE</h3>
            </div>
          </div>
        </section>

        {/* TECH STACK HUD PANEL */}
        <section className="lg:col-span-12 border border-[#00f2ff]/25 bg-[#0a0a0f]/90 p-5 rounded-none relative">
          <div className="absolute top-0 left-0 w-3 h-3 border-t-2 border-l-2 border-[#00f2ff]"></div>
          <div className="absolute bottom-0 right-0 w-3 h-3 border-b-2 border-r-2 border-[#ff00ff]"></div>
          
          <div className="flex flex-col md:flex-row items-start md:items-center justify-between gap-4">
            <div>
              <div className="text-[10px] text-[#00f2ff] font-mono tracking-widest font-extrabold uppercase">
                ACTIVE PIPELINE TARGET REGISTERED
              </div>
              <h2 className="text-sm font-bold font-mono text-white mt-1 uppercase tracking-widest flex items-center gap-2">
                <span className="text-[#00f2ff]">&gt;&gt;</span> Native Android with Kotlin and Jetpack Compose
              </h2>
            </div>
            
            <div className="flex flex-wrap gap-2">
              <span className="px-2.5 py-1 bg-[#050505] border border-[#00f2ff]/40 text-[#00f2ff] text-[9px] font-mono font-bold tracking-widest uppercase">
                UI: Jetpack Compose
              </span>
              <span className="px-2.5 py-1 bg-[#050505] border border-[#ff00ff]/40 text-[#ff00ff] text-[9px] font-mono font-bold tracking-widest uppercase">
                DB backend: Room DB
              </span>
              <span className="px-2.5 py-1 bg-[#050505] border border-[#00f2ff]/40 text-emerald-400 text-[9px] font-mono font-bold tracking-widest uppercase">
                concurrency: Coroutines &amp; Flow
              </span>
              <span className="px-2.5 py-1 bg-[#050505] border border-violet-500/45 text-violet-400 text-[9px] font-mono font-bold tracking-widest uppercase">
                Transport: OkHttp3
              </span>
            </div>
          </div>
          <p className="text-[10.5px] text-slate-400 mt-2.5 leading-relaxed font-mono">
            Structured in <span className="text-white hover:text-[#00f2ff] transition-colors font-bold duration-150">Clean Architecture Core, Data, Domain, and Presentation Layers</span>. The mobile device simulator displays mock-wired Room database transaction outputs and async HTTP scanner runs, compiled instantly as standalone Android Studio source Gradle configurations (.zip target download active).
          </p>
        </section>

        {/* COL 2: SMARTPHONE SIMULATOR (LEFT COLUMN) */}
        <section className="lg:col-span-5 flex flex-col items-center">
          <div className="w-full max-w-[370px]">
            
            {/* PHONE FRAME HOUSING WIRED TO THEME */}
            <div className="w-full border-4 border-[#00f2ff] bg-[#050505] rounded-[30px] p-2 relative shadow-[0_0_35px_rgba(0,242,255,0.2)] min-h-[660px] flex flex-col">
              
              {/* Phone hardware outer accents */}
              <div className="absolute top-2.5 left-1/2 -translate-x-1/2 w-28 h-5 bg-[#050505] border border-[#00f2ff]/40 rounded-full flex items-center justify-center z-20">
                <div className="w-10 h-1 bg-[#00f2ff]/60 rounded-full"></div>
              </div>

              {/* simulated phone screen */}
              <div className="flex-1 flex flex-col bg-[#050505] text-[#cfd3dd] font-mono text-xs overflow-hidden rounded-[24px] pt-7 relative border border-[#00f2ff]/20">
                
                {/* Phone Status Bar */}
                <div className="flex justify-between items-center px-4 py-2 text-[9px] text-[#00f2ff]/60 border-b border-[#00f2ff]/10">
                  <span className="font-bold tracking-widest">09:56 AM // RADAR</span>
                  <div className="flex items-center gap-1.5">
                    <Wifi className="w-3 h-3 text-[#00f2ff] animate-pulse" />
                    <span className="text-[#00f2ff] font-bold">5G_SECURED</span>
                  </div>
                </div>

                {/* Composable Jetpack Compose Simulated App Content */}
                <div className="flex-1 flex flex-col p-3.5 relative overflow-hidden bg-[#050505]">
                  
                  {/* Neon System Header */}
                  <div className="mb-4 border-2 border-[#00f2ff]/40 bg-[#00f2ff]/5 p-3 rounded-none flex justify-between items-center relative corner-accent">
                    <div>
                      <h4 className="text-[10px] font-bold text-[#00f2ff] tracking-widest flex items-center gap-1.5 uppercase">
                        <Shield className="w-3.5 h-3.5 text-[#00f2ff]" />
                        NETCHECKER // MOBILE
                      </h4>
                      <p className="text-[8px] text-emerald-400 font-bold mt-0.5 tracking-wider">RADAR: ACTIVE_SCAN</p>
                    </div>
                    <span className="text-[9px] text-[#ff00ff] font-bold bg-[#ff00ff]/5 px-2 py-0.5 rounded-none border border-[#ff00ff]/40 tracking-wider">
                      CF_PROBE
                    </span>
                  </div>

                  {/* ACTIVE VIEW TAB SELECTOR */}
                  {activePhoneTab === "SCANNER" ? (
                    
                    /* TAB 1: CLOUDFLARE IP RADAR */
                    <div className="flex-1 flex flex-col gap-3 min-h-0">
                      
                      {/* Radar Sweep & Scan stats */}
                      <div className="grid grid-cols-12 gap-2.5 bg-[#0a0a0f] border border-[#00f2ff]/25 p-3 rounded-none relative">
                        {/* Diagonal slash background accents */}
                        <div className="absolute top-0 right-0 w-3 h-3 bg-gradient-to-tr from-transparent to-[#00f2ff]"></div>
                        
                        {/* Radar Sweep Circle canvas */}
                        <div className="col-span-12 xs:col-span-5 flex items-center justify-center py-2">
                          <div className="relative w-24 h-24 border-2 border-[#00f2ff]/20 rounded-full bg-[#050505] flex items-center justify-center overflow-hidden">
                            
                            {/* concentric diagnostic bands */}
                            <div className="absolute inset-0 border border-dashed border-[#00f2ff]/15 rounded-full scale-75"></div>
                            <div className="absolute inset-0 border border-dashed border-[#ff00ff]/15 rounded-full scale-50"></div>
                            <div className="absolute inset-0 border border-[#00f2ff]/5 rounded-full scale-105"></div>
                            
                            {/* rotating sweep gun-sight */}
                            {isScanning && (
                              <motion.div 
                                className="absolute w-1/2 h-0.5 bg-gradient-to-r from-transparent via-[#00f2ff]/80 to-[#00f2ff] origin-left left-1/2 top-1/2 -mt-0.5"
                                animate={{ rotate: 360 }}
                                transition={{ repeat: Infinity, duration: 2.2, ease: "linear" }}
                              ></motion.div>
                            )}
                            
                            {/* inner indicator text */}
                            <div className="text-[9px] font-extrabold text-[#00f2ff] text-center z-10 leading-none tracking-widest">
                              {isScanning ? (
                                <span className="animate-pulse cyan-glow">SCANNING</span>
                              ) : (
                                <span className="text-gray-500">STANDBY</span>
                              )}
                            </div>
                          </div>
                        </div>

                        {/* Scanner Engine parameters */}
                        <div className="col-span-12 flex flex-col justify-between text-[9px] space-y-1.5 pt-2 border-t border-[#00f2ff]/10">
                          <div className="space-y-0.5">
                            <span className="text-[8px] text-slate-500 block uppercase tracking-widest">ACTIVE TARGET</span>
                            <div className="text-[#00f2ff] truncate font-bold text-[10px]" id="sim-current-ip">
                              &gt; {isScanning ? currentIp : "SYSTEM STANDBY"}
                            </div>
                          </div>
                          <div>
                            <span className="text-[8px] text-slate-500 block uppercase tracking-widest">CONCURRENCY MODEL</span>
                            <span className="text-white font-semibold">8 Coroutines // Semaphore</span>
                          </div>
                          <div className="pt-2 border-t border-[#00f2ff]/10">
                            <button
                              onClick={isScanning ? handleStopScan : handleStartScan}
                              className={`w-full py-1.5 rounded-none text-center font-bold text-[9px] uppercase tracking-widest transition-all duration-300 border ${
                                isScanning 
                                  ? "bg-gradient-to-r from-[#ff00ff]/20 to-[#ff00ff]/40 text-white border-[#ff00ff] hover:bg-[#ff00ff]/50 cursor-pointer text-shadow" 
                                  : "bg-[#00f2ff]/10 text-[#00f2ff] border-[#00f2ff]/60 hover:bg-[#00f2ff]/30 shadow-[0_0_12px_rgba(0,242,255,0.2)] cursor-pointer"
                              }`}
                              id="sim-initiate-scan-btn"
                            >
                              {isScanning ? "// TERMINATE SCAN" : "// INITIATE SCAN"}
                            </button>
                          </div>
                        </div>
                      </div>

                      {/* Room verified targets header */}
                      <div className="flex justify-between items-center text-[9px] mt-1">
                        <span className="text-[#00f2ff] font-bold tracking-widest uppercase">VERIFIED CLEAN IPS (ROOM)</span>
                        {savedIps.length > 0 && (
                          <div className="flex items-center gap-2">
                            <button 
                               onClick={handleExportIpsJson}
                               className="text-[#00f2ff] hover:underline font-bold transition-all text-[8px] tracking-widest cursor-pointer"
                            >
                              [EXPORT JSON]
                            </button>
                            <span className="text-gray-700">|</span>
                            <button 
                               onClick={handleClearAllIps}
                               className="text-[#ff00ff] hover:underline font-bold transition-all text-[8px] tracking-widest cursor-pointer"
                            >
                              [WIPE ALL]
                            </button>
                          </div>
                        )}
                      </div>

                      {/* Operator selection pills & Sort Toggle for IPs */}
                      <div className="flex justify-between items-center gap-1">
                        <div className="flex items-center gap-1 overflow-x-auto select-none custom-scrollbar">
                          {["ALL", "MCI", "Irancell", "Wi-Fi"].map((op) => (
                            <button
                              key={op}
                              onClick={() => setSelectedOperator(op)}
                              className={`px-1.5 py-0.5 text-[8px] font-mono border duration-100 uppercase ${
                                selectedOperator === op
                                  ? "bg-[#00f2ff]/15 border-[#00f2ff] text-[#00f2ff]"
                                  : "bg-[#050505] border-[#00f2ff]/15 text-gray-500 hover:text-gray-300"
                              }`}
                            >
                              {op}
                            </button>
                          ))}
                        </div>

                        <button
                          onClick={() => setSortIpsByPing(!sortIpsByPing)}
                          className={`px-1.5 py-0.5 text-[8px] font-mono border duration-100 uppercase shrink-0 ${
                            sortIpsByPing
                              ? "bg-[#39ff14]/15 border-[#39ff14] text-[#39ff14]"
                              : "bg-[#050505] border-[#00f2ff]/15 text-gray-500 hover:text-gray-300"
                          }`}
                        >
                          Sort: Ping
                        </button>
                      </div>

                      {/* Clean IP Room Table Container */}
                      <div className="flex-1 bg-[#0a0a0f] border border-[#00f2ff]/15 rounded-none p-2 overflow-y-auto max-h-[290px] space-y-1.5 custom-scrollbar">
                        {(() => {
                          const displayedIps = savedIps
                            .filter((ip) => {
                              if (selectedOperator === "ALL") return true;
                              return ip.operatorType === selectedOperator;
                            })
                            .sort((a, b) => {
                              if (sortIpsByPing) return a.pingMs - b.pingMs;
                              return (b.lastChecked || 0) - (a.lastChecked || 0);
                            });

                          if (displayedIps.length === 0) {
                            return (
                              <div className="h-full flex flex-col items-center justify-center p-4 text-center">
                                <Database className="w-5 h-5 text-gray-700 mb-2" />
                                <p className="text-[8px] text-gray-500 uppercase tracking-wider">No matching Clean IPs found in Room.</p>
                              </div>
                            );
                          }

                          return displayedIps.map((ip) => (
                            <div 
                              key={ip.ipAddress}
                              className="flex justify-between items-center p-2 bg-[#050505] border border-[#00f2ff]/15 hover:border-[#00f2ff]/40 rounded-none duration-100"
                            >
                              <div className="flex items-center gap-1.5">
                                <span className="w-1.5 h-1.5 rounded-none bg-[#00f2ff] shadow-[0_0_6px_#00f2ff]"></span>
                                <span className="text-[10px] text-white font-bold tracking-wide font-mono">{ip.ipAddress}</span>
                                <span className="text-[8px] bg-[#00f2ff]/10 text-[#00f2ff]/80 px-1 border border-[#00f2ff]/15 font-mono">
                                  {ip.operatorType || "ALL"}
                                </span>
                              </div>
                              <div className="flex items-center gap-2">
                                <span className="text-[10px] font-extrabold text-[#39ff14]">{ip.pingMs} ms</span>
                                <button 
                                  onClick={() => handleDeleteIp(ip.ipAddress)}
                                  className="text-gray-500 hover:text-[#ff00ff] transition"
                                >
                                  <Trash2 className="w-3 h-3" />
                                </button>
                              </div>
                            </div>
                          ));
                        })()}
                      </div>
                    </div>
                  ) : (
                    
                    /* TAB 2: PROXY / INJECT TAB */
                    <div className="flex-1 flex flex-col gap-3 min-h-0">
                      
                      {/* Sub header triggers */}
                      <div className="flex justify-between items-center text-[9px] border-b border-[#00f2ff]/10 pb-2">
                        <span className="text-[#00f2ff] font-bold uppercase tracking-widest">SURGERY OVERLAYS</span>
                        <button
                          onClick={() => setIsAddingProxy(!isAddingProxy)}
                          className="text-[#ff00ff] hover:underline flex items-center gap-1 font-bold text-[8px] uppercase tracking-widest"
                        >
                          {isAddingProxy ? "[CLOSE OVERLAY]" : "[+ REGISTER GATE]"}
                        </button>
                      </div>

                      {/* Add proxy form overlay */}
                      <AnimatePresence>
                        {isAddingProxy && (
                          <motion.form 
                            onSubmit={handleAddProxy}
                            initial={{ height: 0, opacity: 0 }}
                            animate={{ height: "auto", opacity: 1 }}
                            exit={{ height: 0, opacity: 0 }}
                            className="bg-[#0a0a0f] border-2 border-[#ff00ff]/30 p-2.5 rounded-none overflow-hidden space-y-2.5"
                          >
                            <div className="space-y-1">
                              <label className="text-[8px] text-slate-400 block font-bold uppercase tracking-widest">REMARKS / GATE NAME</label>
                              <input 
                                type="text" 
                                placeholder="e.g. HK CDN Gateway"
                                value={remarksInput}
                                onChange={(e) => setRemarksInput(e.target.value)}
                                className="w-full bg-[#050505] border border-[#ff00ff]/30 rounded-none px-2 py-1 text-[10px] text-white focus:outline-none focus:border-[#ff00ff] font-mono"
                              />
                            </div>
                            <div className="space-y-1 font-mono">
                              <label className="text-[8px] text-slate-400 block font-bold uppercase tracking-widest">RAW VALUE / CF PAYLOAD</label>
                              <input 
                                type="text" 
                                placeholder="vless://cf-bypass-node@1.1.1.1:443"
                                value={configInput}
                                onChange={(e) => setConfigInput(e.target.value)}
                                className="w-full bg-[#050505] border border-[#ff00ff]/30 rounded-none px-2 py-1 text-[10px] text-white focus:outline-none focus:border-[#ff00ff] font-mono"
                              />
                            </div>
                            <button
                              type="submit"
                              className="w-full bg-gradient-to-r from-[#ff00ff]/80 to-[#ff00ff] hover:from-[#ff00ff] hover:to-[#ff00ff]/90 text-black py-1 rounded-none font-bold text-[9px] uppercase tracking-widest transition duration-150"
                            >
                              INJECT TO ROOM DB
                            </button>
                          </motion.form>
                        )}
                      </AnimatePresence>

                      {/* Action buttons panel: TEST ALL & FILTERS */}
                      <div className="flex flex-col gap-1.5 mt-0.5">
                        <div className="flex gap-1.5">
                          <button
                            onClick={handleBatchTestProxies}
                            disabled={isTestingProxies || savedProxies.length === 0}
                            className={`flex-1 py-1 px-1.5 text-[8px] font-bold font-mono tracking-wider border rounded-none uppercase transition select-none ${
                              isTestingProxies
                                ? "bg-black border-[#ff00ff]/30 text-slate-500 cursor-not-allowed"
                                : "bg-gradient-to-r from-[#ff00ff]/20 to-[#ff00ff]/10 hover:from-[#ff00ff]/35 border-[#ff00ff] text-white"
                            }`}
                          >
                            {isTestingProxies ? `TESTING (${proxiesTestedCount}/${proxiesTotalCount})` : "⚡ TEST ALL CONFIGS"}
                          </button>

                          <button
                            onClick={() => setSortProxiesByPing(!sortProxiesByPing)}
                            className={`px-1.5 py-1 text-[8px] font-mono border uppercase shrink-0 transition duration-100 ${
                              sortProxiesByPing 
                                ? "bg-[#39ff14]/15 border-[#39ff14] text-[#39ff14]" 
                                : "bg-[#050505] border-[#00f2ff]/15 text-gray-500"
                            }`}
                          >
                            Sort: Ping
                          </button>

                          <button
                            onClick={() => setFilterWorkingProxies(!filterWorkingProxies)}
                            className={`px-1.5 py-1 text-[8px] font-mono border uppercase shrink-0 transition duration-100 ${
                              filterWorkingProxies 
                                ? "bg-[#00f2ff]/15 border-[#00f2ff] text-[#00f2ff]" 
                                : "bg-[#050505] border-[#00f2ff]/15 text-gray-500"
                            }`}
                          >
                            Working Only
                          </button>
                        </div>

                        {/* Loading progress bar */}
                        {isTestingProxies && (
                          <div className="w-full bg-[#0a0a0f] border border-[#ff00ff]/25 p-1 font-mono text-[7px] space-y-1">
                            <div className="flex justify-between text-[#ff00ff] font-bold">
                              <span>BATCH PROBING SECURE LINKS...</span>
                              <span>{Math.round((proxiesTestedCount / proxiesTotalCount) * 100)}%</span>
                            </div>
                            <div className="w-full bg-[#ff00ff]/10 h-1 rounded-none overflow-hidden">
                              <div 
                                className="bg-[#ff00ff] h-full duration-150 transition-all shadow-[0_0_8px_#ff00ff]" 
                                style={{ width: `${(proxiesTestedCount / proxiesTotalCount) * 100}%` }}
                              />
                            </div>
                          </div>
                        )}

                        {/* Export Working configurations panel */}
                        <div className="flex items-center justify-between border border-[#ff00ff]/20 bg-[#ff00ff]/5 p-1 text-[7px] font-mono select-none">
                          <span className="text-[#ff00ff] font-bold uppercase tracking-wider pl-1 font-mono">EXPORT GATES:</span>
                          <div className="flex gap-1">
                            <button
                              onClick={() => handleExportWorkingProxies("V2RAY")}
                              className="px-1 py-0.5 border border-[#ff00ff]/50 hover:bg-[#ff00ff]/20 text-[#ff00ff] uppercase font-bold text-[7px] transition duration-100"
                            >
                              V2Ray
                            </button>
                            <button
                              onClick={() => handleExportWorkingProxies("CLASH")}
                              className="px-1 py-0.5 border border-[#39ff14]/50 hover:bg-[#39ff14]/20 text-[#39ff14] uppercase font-bold text-[7px] transition duration-100"
                            >
                              Clash
                            </button>
                            <button
                              onClick={() => handleExportWorkingProxies("SUBSCRIPTION")}
                              className="px-1 py-0.5 border border-[#00f2ff]/50 hover:bg-[#00f2ff]/20 text-[#00f2ff] uppercase font-bold text-[7px] transition duration-100"
                            >
                              Sub
                            </button>
                          </div>
                        </div>
                      </div>

                      {/* Proxy configs list */}
                      <div className="flex-1 bg-[#0a0a0f] border border-[#00f2ff]/15 rounded-none p-2 overflow-y-auto max-h-[290px] space-y-2 custom-scrollbar">
                        {(() => {
                          const displayedProxies = savedProxies
                            .filter((p) => {
                              if (filterWorkingProxies) return p.isWorking;
                              return true;
                            })
                            .sort((a, b) => {
                              if (sortProxiesByPing) {
                                const aVal = a.isWorking && a.currentPing > 0 ? a.currentPing : 99999;
                                const bVal = b.isWorking && b.currentPing > 0 ? b.currentPing : 99999;
                                return aVal - bVal;
                              }
                              return 0; // maintain original database insertion sequence
                            });

                          if (displayedProxies.length === 0) {
                            return (
                              <div className="h-full flex flex-col items-center justify-center p-4 text-center">
                                <Network className="w-5 h-5 text-gray-700 mb-2" />
                                <p className="text-[8px] text-gray-500 uppercase tracking-wider">No matching profiles found.</p>
                              </div>
                            );
                          }

                           return displayedProxies.map((proxy) => (
                            <div 
                              key={proxy.id}
                              onClick={() => setSelectedProxyId(proxy.id)}
                              className={`p-2 rounded-none flex flex-col gap-2 duration-100 cursor-pointer border ${
                                selectedProxyId === proxy.id
                                  ? "bg-[#07070d] border-[#ff00ff]/60 shadow-[0_0_10px_rgba(255,0,255,0.15)]"
                                  : "bg-[#050505] border-[#00f2ff]/10 hover:border-[#00f2ff]/35"
                              }`}
                            >
                              <div className="flex items-center justify-between gap-2 w-full">
                                <div className="flex-1 min-w-0">
                                  <div className="text-[10px] text-white font-bold truncate flex items-center gap-1">
                                    <Shield className={`w-3 h-3 shrink-0 ${selectedProxyId === proxy.id ? "text-[#ff00ff]" : "text-[#00f2ff]"}`} />
                                    {proxy.remarks}
                                  </div>
                                  <div className="text-[8px] text-[#00f2ff]/50 truncate font-mono mt-0.5">{proxy.rawConfig}</div>
                                </div>

                                <div className="flex items-center gap-1.5 shrink-0" onClick={(e) => e.stopPropagation()}>
                                  <button
                                    onClick={() => {
                                      navigator.clipboard.writeText(proxy.rawConfig);
                                      setRecentlyCopiedId(proxy.id);
                                      setTimeout(() => setRecentlyCopiedId(null), 1500);
                                    }}
                                    className={`px-2 py-0.5 rounded-none text-[8px] font-bold duration-150 flex items-center gap-1 border ${
                                      recentlyCopiedId === proxy.id
                                        ? "bg-[#39ff14]/15 text-[#39ff14] border-[#39ff14]/50"
                                        : "bg-[#050505] text-[#39ff14] border-[#39ff14]/20 hover:border-[#39ff14]/60"
                                    }`}
                                  >
                                    {recentlyCopiedId === proxy.id ? "COPIED!" : "COPY"}
                                  </button>

                                  <button
                                    onClick={() => handleTestProxyPing(proxy)}
                                    className={`px-2 py-0.5 rounded-none text-[8px] font-bold duration-150 flex items-center gap-1 border ${
                                      proxy.currentPing === 0
                                        ? "bg-[#00f2ff]/10 text-[#00f2ff] border-[#00f2ff]/30"
                                        : proxy.currentPing > 0
                                        ? "bg-[#050505] text-[#00f2ff] border-[#00f2ff]/50"
                                        : "bg-[#050505] text-[#ff00ff] border-[#ff00ff]/20 hover:border-[#ff00ff]/60"
                                    }`}
                                  >
                                    {proxy.currentPing === 0 ? (
                                      <>
                                        <RefreshCw className="w-2.5 h-2.5 animate-spin text-[#00f2ff]" />
                                        PINGING
                                      </>
                                    ) : proxy.currentPing > 0 ? (
                                      `${proxy.currentPing} ms`
                                    ) : (
                                      "PING TEST"
                                    )}
                                  </button>

                                  <button
                                    onClick={() => handleDeleteProxy(proxy.id)}
                                    className="text-gray-500 hover:text-[#ff00ff] p-0.5"
                                  >
                                    <XCircle className="w-3.5 h-3.5" />
                                  </button>
                                </div>
                              </div>

                              {/* Selected Proxy Expanded Detail / D3-based Line Chart */}
                              {selectedProxyId === proxy.id && (
                                <motion.div 
                                  initial={{ height: 0, opacity: 0 }}
                                  animate={{ height: "auto", opacity: 1 }}
                                  exit={{ height: 0, opacity: 0 }}
                                  className="pt-1.5 border-t border-[#00f2ff]/10"
                                  onClick={(e) => e.stopPropagation()}
                                >
                                  <div className="flex justify-between items-center mb-1">
                                    <span className="text-[7.5px] font-bold text-[#ff00ff] tracking-widest uppercase">
                                      {proxy.remarks} LATENCY GRAPH
                                    </span>
                                    <span className="text-[6.5px] text-slate-500 font-mono">
                                      Avg: {Math.round(
                                        (proxy.pingHistory?.filter(h => h.ping > 0).reduce((acc, curr) => acc + curr.ping, 0) || 0) /
                                        (proxy.pingHistory?.filter(h => h.ping > 0).length || 1)
                                      ) || 0} ms
                                    </span>
                                  </div>
                                  <ProxyPingChart history={proxy.pingHistory || []} />

                                  {/* Cybersecurity Throughput Analyzer module */}
                                  <div className="border-t border-[#00f2ff]/10 my-2" />
                                  <div className="flex justify-between items-center mb-1.5">
                                    <span className="text-[7.5px] font-bold text-[#00f2ff] tracking-widest uppercase flex items-center gap-1">
                                      <Gauge className="w-2.5 h-2.5 text-[#00f2ff]" />
                                      THROUGHPUT CAPACITY ANALYZER
                                    </span>
                                    {proxy.throughputResult && (
                                      <span className="text-[6px] text-zinc-500 font-mono uppercase">
                                        CALIBRATED: {proxy.throughputResult.testTime}
                                      </span>
                                    )}
                                  </div>

                                  {testingThroughputId === proxy.id ? (
                                    <div className="bg-[#030306] border border-[#ff00ff]/20 p-2 font-mono text-[7px] space-y-1.5 border-dashed">
                                      <div className="flex justify-between text-[#00f2ff] font-bold">
                                        <span className="animate-pulse flex items-center gap-1">
                                          <RefreshCw className="w-2.5 h-2.5 animate-spin text-[#00f2ff]" />
                                          {throughputPhase}
                                        </span>
                                        <span>{throughputProgress}%</span>
                                      </div>
                                      <div className="w-full bg-[#ff00ff]/10 h-1.5 rounded-none overflow-hidden border border-[#00f2ff]/10">
                                        <div 
                                          className="bg-gradient-to-r from-[#00f2ff] to-[#ff00ff] h-full duration-75 transition-all shadow-[0_0_10px_#ff00ff]" 
                                          style={{ width: `${throughputProgress}%` }}
                                        />
                                      </div>
                                      <div className="text-[5.5px] text-zinc-500 uppercase leading-none font-mono">
                                        Evaluating channel overhead with port 443 TCP payload metrics.
                                      </div>
                                    </div>
                                  ) : proxy.throughputResult ? (
                                    proxy.throughputResult.packetLoss === 100 ? (
                                      <div className="bg-[#020204] border border-red-500/20 p-2 flex flex-col gap-1 relative font-mono">
                                        <div className="flex justify-between items-center border-b border-red-500/10 pb-1">
                                          <span className="text-[6px] text-red-500 font-bold uppercase tracking-widest flex items-center gap-1">
                                            <XCircle className="w-2.5 h-2.5 text-red-500 animate-pulse" />
                                            PIPELINE FAILED // PACKET LOSS: 100%
                                          </span>
                                          <span className="text-[5.5px] text-zinc-500 font-mono">
                                            RTT: ERR
                                          </span>
                                        </div>
                                        <p className="text-[6.5px] text-zinc-400 leading-tight">
                                          TCP connection timeout. Network dropped 100% of test packet probes. Please verify proxy ping latency is active and run a standard PING TEST first.
                                        </p>
                                        <button
                                          onClick={() => handleRunThroughputTest(proxy)}
                                          disabled={testingThroughputId !== null}
                                          className="w-full mt-1.5 py-1 bg-red-950/15 border border-red-500/30 hover:bg-red-950/30 hover:border-red-500 text-red-400 text-[7px] font-bold tracking-widest uppercase transition duration-150 rounded-none cursor-pointer disabled:opacity-30"
                                        >
                                          RETEST PIPELINE
                                        </button>
                                      </div>
                                    ) : (
                                      <div className="bg-[#020204] border border-[#00f2ff]/15 p-2 grid grid-cols-3 gap-1 grid-rows-1 relative font-mono">
                                        <div className="border-r border-[#00f2ff]/10 pr-1 flex flex-col justify-center">
                                          <span className="text-[5.5px] text-zinc-500 uppercase tracking-widest block font-bold">MAX DOWNLOAD</span>
                                          <span className="text-[11px] font-bold text-[#39ff14] tracking-tight truncate flex items-center gap-0.5">
                                            <Zap className="w-2.5 h-2.5 text-[#39ff14] animate-pulse" />
                                            {proxy.throughputResult.speedMbps} <span className="text-[7px] text-[#39ff14]/60">Mbps</span>
                                          </span>
                                        </div>
                                        
                                        <div className="border-r border-[#00f2ff]/10 px-1 flex flex-col justify-center">
                                          <span className="text-[5.5px] text-zinc-500 uppercase tracking-widest block font-bold">PACKET LOSS</span>
                                          <span className={`text-[10px] font-bold tracking-tight truncate ${proxy.throughputResult.packetLoss > 1.2 ? "text-red-400" : proxy.throughputResult.packetLoss > 0.6 ? "text-amber-400" : "text-[#00f2ff]"}`}>
                                            {proxy.throughputResult.packetLoss}%
                                          </span>
                                        </div>

                                        <div className="pl-1 flex flex-col justify-center items-end">
                                          <button
                                            onClick={() => handleRunThroughputTest(proxy)}
                                            disabled={testingThroughputId !== null}
                                            className="w-full py-1.5 bg-[#00f2ff]/5 border border-[#00f2ff]/30 hover:bg-[#00f2ff]/15 hover:border-[#00f2ff]/75 text-[#00f2ff] text-[7.5px] font-bold tracking-widest uppercase transition duration-150 rounded-none cursor-pointer disabled:opacity-30"
                                          >
                                            RETEST
                                          </button>
                                        </div>
                                      </div>
                                    )
                                  ) : (
                                    <div className="bg-[#020204] border border-[#ff00ff]/10 p-2 flex items-center justify-between gap-1">
                                      <div className="flex-1">
                                        <span className="text-[6.3px] text-zinc-400 font-mono block leading-relaxed pr-1 leading-snug">
                                          Calculate theoretical maximum TCP download capacity via **Mathis TCP rate equation** based on current link RTT samples and mock packet loss variances.
                                        </span>
                                      </div>
                                      
                                      <button
                                        onClick={() => handleRunThroughputTest(proxy)}
                                        disabled={testingThroughputId !== null}
                                        className="px-2 py-1.5 bg-[#ff00ff]/5 hover:bg-[#ff00ff]/15 border border-[#ff00ff]/40 hover:border-[#ff00ff]/80 text-[#ff00ff] text-[8px] font-bold font-mono tracking-wider uppercase transition duration-150 rounded-none cursor-pointer shrink-0 disabled:opacity-40"
                                      >
                                        RUN TEST
                                      </button>
                                    </div>
                                  )}
                                </motion.div>
                              )}
                            </div>
                          ));
                        })()}
                      </div>
                    </div>
                  )}

                  {/* BOTTOM COMPOSE BAR SIMULATOR */}
                  <div className="h-11 mt-auto border-t-2 border-[#00f2ff]/30 bg-[#050505] -mx-3.5 -mb-3.5 flex relative">
                    <button
                      onClick={() => setActivePhoneTab("SCANNER")}
                      className={`flex-1 flex flex-col items-center justify-center relative duration-150 cursor-pointer ${
                        activePhoneTab === "SCANNER" ? "text-[#00f2ff] bg-[#00f2ff]/5" : "text-gray-500 hover:text-gray-300"
                      }`}
                    >
                      <Layers className="w-3.5 h-3.5" />
                      <span className="text-[8px] font-bold uppercase mt-1 tracking-widest">IP RADAR</span>
                      {activePhoneTab === "SCANNER" && (
                        <div className="absolute top-0 left-0 right-0 h-0.5 bg-[#00f2ff]"></div>
                      )}
                    </button>
                    <button
                      onClick={() => setActivePhoneTab("PROXIES")}
                      className={`flex-1 flex flex-col items-center justify-center relative duration-150 cursor-pointer ${
                        activePhoneTab === "PROXIES" ? "text-[#ff00ff] bg-[#ff00ff]/5" : "text-gray-500 hover:text-gray-300"
                      }`}
                    >
                      <Network className="w-3.5 h-3.5" />
                      <span className="text-[8px] font-bold uppercase mt-1 tracking-widest">INJECT GATE</span>
                      {activePhoneTab === "PROXIES" && (
                        <div className="absolute top-0 left-0 right-0 h-0.5 bg-[#ff00ff]"></div>
                      )}
                    </button>
                  </div>

                </div>
              </div>
            </div>

            {/* Simulated hardware trigger details */}
            <div className="text-center mt-4 text-[9px] text-gray-500 font-mono tracking-wide">
              <span className="text-[#ff00ff] font-extrabold">&#9670;</span> Pinging IPs executes real port 443 TCP connections in Node back-end. Highly sandboxed environment.
            </div>
          </div>
        </section>

        {/* COL 3: CLEAN ARCHITECTURE CODE EXPLORER STUDIO (RIGHT COLUMN) */}
        <section className="lg:col-span-7 flex flex-col">
          
          {/* STUDIO HOUSING FRAMEWORK WIRED TO THEME */}
          <div className="flex-1 border-2 border-[#00f2ff]/20 bg-[#0a0a0f]/80 rounded-none overflow-hidden flex flex-col min-h-[580px] relative">
            
            {/* Folder tab head browser with clean cyan border */}
            <div className="bg-[#050505] border-b border-[#00f2ff]/20 px-4 py-3.5 flex flex-col sm:flex-row justify-between items-start sm:items-center gap-3">
              <div className="flex items-center gap-2">
                <Code className="text-[#00f2ff] w-4.5 h-4.5" />
                <span className="text-xs font-mono font-bold uppercase tracking-wider text-slate-200">
                  Clean Architecture Project Navigator
                </span>
              </div>
              
              <div className="flex items-center gap-2 text-xs">
                <span className="text-slate-500 font-mono text-[9px] uppercase tracking-wider">SDK Targets</span>
                <span className="px-2.5 py-0.5 bg-[#050505] border border-[#00f2ff]/40 rounded-none font-mono text-[#00f2ff] text-[9px] font-bold uppercase tracking-wider">
                  Android Standard
                </span>
              </div>
            </div>

            {/* Split layout - File Tree on Left, Viewer on Right */}
            <div className="flex-1 grid grid-cols-12 md:divide-x md:divide-[#00f2ff]/20 min-h-0">
              
              {/* FILE DIRECTORY SELECTOR */}
              <div className="col-span-12 md:col-span-4 bg-[#050505] p-3.5 overflow-y-auto max-h-[500px] md:max-h-none space-y-4 custom-scrollbar">
                
                {/* Categorized file listing items with Geometric tags */}
                {Array.from(new Set(ANDROID_PROJECT_FILES.map(f => f.category))).map((cat) => (
                  <div key={cat} className="space-y-1.5">
                    <span className="text-[9px] font-mono text-[#00f2ff] block uppercase tracking-widest font-extrabold pb-1.5 border-b border-[#00f2ff]/15 flex items-center gap-2">
                      <Layers className="w-2.5 h-2.5 text-[#00f2ff]" />
                      {cat}
                    </span>
                    <div className="space-y-1 pt-1">
                      {ANDROID_PROJECT_FILES.map((file, idx) => {
                        if (file.category !== cat) return null;
                        const isSelected = selectedFileIndex === idx;
                        return (
                          <button
                            key={file.path}
                            onClick={() => setSelectedFileIndex(idx)}
                            className={`w-full text-left font-mono text-[11px] px-2.5 py-1.5 rounded-none transition duration-150 flex items-center justify-between group cursor-pointer ${
                              isSelected 
                                ? "bg-[#00f2ff]/10 border-l border-[#00f2ff] text-white font-bold" 
                                : "text-gray-400 hover:bg-[#ff00ff]/5 hover:text-white"
                            }`}
                          >
                            <span className="truncate pr-1 font-mono">{file.name}</span>
                            <ChevronRight className={`w-3 h-3 text-slate-600 transition duration-200 group-hover:text-[#00f2ff] ${
                              isSelected ? "text-[#00f2ff]" : ""
                            }`} />
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>

              {/* MONOSPACE CODE DISPLAY */}
              <div className="col-span-12 md:col-span-8 flex flex-col bg-[#050505] min-h-[350px] md:min-h-0">
                
                {/* Code viewport control tab */}
                <div className="bg-[#050505] border-b border-[#00f2ff]/10 px-4 py-2 flex justify-between items-center shrink-0">
                  <div className="flex items-center gap-2">
                    <span className="text-[10px] text-[#00f2ff] font-mono animate-pulse">&#9670;</span>
                    <span className="text-[10px] font-mono text-gray-300 font-bold tracking-tight truncate max-w-[200px]">
                      {selectedFile.path}
                    </span>
                  </div>

                  <button
                    onClick={() => handleCopyCode(selectedFile.content)}
                    className="flex items-center gap-1.5 bg-[#0a0a0f] hover:bg-[#00f2ff]/10 border border-[#00f2ff]/20 text-gray-300 font-mono text-[9px] px-3 py-1 rounded-none transition-all duration-200 cursor-pointer uppercase tracking-wider"
                  >
                    {copiedFile ? (
                      <>
                        <CheckCircle2 className="w-3 h-3 text-[#00f2ff] animate-bounce" />
                        <span className="text-[#00f2ff] font-bold">COPIED</span>
                      </>
                    ) : (
                      <>
                        <Copy className="w-3 h-3 text-gray-400 group-hover:text-white" />
                        <span>Copy Block</span>
                      </>
                    )}
                  </button>
                </div>

                {/* Plain-text code block syntax representation */}
                <div className="flex-1 p-4 overflow-auto font-mono text-[11px] bg-[#050505] relative custom-scrollbar border-t border-[#00f2ff]/5">
                  <pre className="text-slate-300 whitespace-pre leading-relaxed select-text font-mono">
                    <code>{selectedFile.content}</code>
                  </pre>
                </div>
              </div>

            </div>

          </div>
        </section>

      </main>

      {/* DETAILED CLEAN ARCHITECTURE DESCRIPTIONS */}
      <footer className="border-t border-[#00f2ff]/20 bg-[#050505] px-6 py-10 mt-12 text-sm text-gray-500 relative">
        <div className="absolute top-0 left-1/2 -translate-x-1/2 w-48 h-[1px] bg-gradient-to-r from-transparent via-[#00f2ff] to-transparent"></div>
        
        <div className="max-w-7xl mx-auto grid grid-cols-1 md:grid-cols-4 gap-8 font-mono text-xs">
          <div className="space-y-2">
            <h4 className="text-white font-bold tracking-widest uppercase text-[10px] text-[#00f2ff] flex items-center gap-1.5">
              <span>■</span> 1. Core / Network
            </h4>
            <p className="leading-relaxed text-slate-400 font-mono text-[11px]">Establishes OkHttpClient configuration with aggressive custom timeouts tuned for fast probing, and direct request headers designed for IP diagnostics with minimal payload overhead.</p>
          </div>
          <div className="space-y-2">
            <h4 className="text-white font-bold tracking-widest uppercase text-[10px] text-[#ff00ff] flex items-center gap-1.5">
              <span>■</span> 2. Data / Room DB
            </h4>
            <p className="leading-relaxed text-slate-400 font-mono text-[11px]">Defines physical schemas CleanIpEntity and ProxyConfigEntity for Room, implementing offline-first storage and Flow queries ordered by round-trip millisecond speeds to fetch fastest nodes.</p>
          </div>
          <div className="space-y-2">
            <h4 className="text-white font-bold tracking-widest uppercase text-[10px] text-[#00f2ff] flex items-center gap-1.5">
              <span>■</span> 3. Domain / UseCases
            </h4>
            <p className="leading-relaxed text-slate-400 font-mono text-[11px]">Specifies clean domain contracts. Launches multi-threaded coroutine worker clusters restricted with Semaphore permits to balance scanner performance while preventing hardware socket exhaust.</p>
          </div>
          <div className="space-y-2">
            <h4 className="text-white font-bold tracking-widest uppercase text-[10px] text-[#ff00ff] flex items-center gap-1.5">
              <span>■</span> 4. Presentation UI
            </h4>
            <p className="leading-relaxed text-slate-400 font-mono text-[11px]">Built with Jetpack Compose. Captures ViewModel flow bounds, displays real-time network logs feed in list arrays, and integrates radar sweep canvas animations styled in custom dark neon themes.</p>
          </div>
        </div>
      </footer>

      {/* EXPORT WORKING CONFIGS MODAL */}
      <AnimatePresence>
        {showExportModal && (
          <div className="fixed inset-0 bg-black/85 backdrop-blur-sm z-[9999] flex items-center justify-center p-4">
            <motion.div
              initial={{ scale: 0.95, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              exit={{ scale: 0.95, opacity: 0 }}
              transition={{ duration: 0.15 }}
              className="bg-[#07070a] border-2 border-[#ff00ff] shadow-[0_0_20px_rgba(255,0,255,0.25)] w-full max-w-lg p-5 font-mono space-y-4"
            >
              <div className="flex justify-between items-center border-[#ff00ff]/20 border-b pb-3">
                <span className="text-[#ff00ff] text-xs font-bold uppercase tracking-widest flex items-center gap-2">
                  <span className="inline-block w-2 bg-[#ff00ff] animate-ping h-2 rounded-full"></span>
                  GATES BATCH EXPORTED
                </span>
                <span className="text-gray-500 text-[10px]">FORMAT: {exportModalFormat}</span>
              </div>

              <p className="text-slate-400 text-[10px] leading-relaxed">
                Nodes listed in "Working Only" category successfully encoded. Use these outbound objects directly inside V2Ray/Clash system proxies or active subscriptions.
              </p>

              {/* Subset Filter Options */}
              <div className="flex flex-col gap-2 bg-[#020204] border border-[#ff00ff]/20 p-2.5 rounded-none">
                <span className="text-[#ff00ff] text-[8px] font-bold uppercase tracking-wider">SUBSET CONFIG FILTERS</span>
                <div className="flex gap-2">
                  <button
                    onClick={() => setExportFilterLowLoss(prev => !prev)}
                    className={`flex-1 py-1 text-[8px] font-bold border transition duration-100 ${
                      exportFilterLowLoss
                        ? "bg-[#ff00ff]/15 text-[#ff00ff] border-[#ff00ff]"
                        : "bg-transparent text-gray-500 border-gray-800 hover:border-gray-600 hover:text-gray-300"
                    }`}
                  >
                    LOSS &lt; 1% [{exportFilterLowLoss ? "ON" : "OFF"}]
                  </button>
                  <button
                    onClick={() => setExportFilterLowPing(prev => !prev)}
                    className={`flex-1 py-1 text-[8px] font-bold border transition duration-100 ${
                      exportFilterLowPing
                        ? "bg-[#00f2ff]/15 text-[#00f2ff] border-[#00f2ff]"
                        : "bg-transparent text-gray-500 border-gray-800 hover:border-gray-600 hover:text-gray-300"
                    }`}
                  >
                    PING &lt; 100ms [{exportFilterLowPing ? "ON" : "OFF"}]
                  </button>
                </div>
              </div>

              <div className="relative">
                <textarea
                  readOnly
                  value={exportModalText}
                  className="w-full bg-[#020204] border border-[#00f2ff]/30 text-[#00f2ff] p-3 text-[9px] font-mono rounded-none h-48 focus:outline-none focus:border-[#00f2ff] custom-scrollbar focus:ring-0"
                />
                <div className="absolute bottom-2 right-2 flex gap-1.5">
                  <button
                    onClick={() => {
                      navigator.clipboard.writeText(exportModalText);
                      setCopiedExport(true);
                      setTimeout(() => setCopiedExport(false), 2000);
                    }}
                    className={`px-3 py-1 text-[9px] font-bold duration-150 rounded-none border leading-none ${
                      copiedExport
                        ? "bg-[#39ff14]/15 text-[#39ff14] border-[#39ff14] shadow-[0_0_8px_rgba(57,255,20,0.2)]"
                        : "bg-black text-[#00f2ff] border-[#00f2ff]/40 hover:border-[#00f2ff]"
                    }`}
                  >
                    {copiedExport ? "COPIED!" : "COPY CONFIG"}
                  </button>
                </div>
              </div>

              <div className="flex justify-end pt-2">
                <button
                  onClick={() => setShowExportModal(false)}
                  className="px-4 py-1.5 text-[9px] font-bold text-gray-400 hover:text-white uppercase transition hover:bg-slate-900 border border-gray-600 hover:border-gray-200"
                >
                  CLOSE DIALOG
                </button>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
}
