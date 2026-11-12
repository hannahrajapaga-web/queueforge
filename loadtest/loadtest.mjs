import { readFileSync } from "node:fs";
import { extname } from "node:path";

const BASE = process.env.BASE || "http://localhost:8080";
const USER = process.env.LT_USER || "admin1";
const PASS = process.env.LT_PASS || "pass1234";
const [imgPath, countArg = "100"] = process.argv.slice(2);

if (!imgPath) {
  console.error('Usage: node loadtest.mjs "<image path>" [count]');
  process.exit(1);
}

const COUNT = Number(countArg);
const buf = readFileSync(imgPath);
const ext = extname(imgPath).slice(1).toLowerCase();
const mime = ext === "png" ? "image/png" : ext === "webp" ? "image/webp" : "image/jpeg";
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function pct(sorted, p) {
  if (sorted.length === 0) return 0;
  return sorted[Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length))];
}

async function login() {
  const res = await fetch(`${BASE}/api/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username: USER, password: PASS }),
  });
  if (!res.ok) throw new Error(`Login failed (${res.status})`);
  return (await res.json()).token;
}

async function main() {
  const token = await login();
  const auth = { Authorization: `Bearer ${token}` };

  // 5% always fail, 15% fail twice then succeed, 80% normal
  const failuresFor = (i) => (i % 20 === 0 ? 9 : i % 20 <= 3 ? 2 : 0);

  const submitAt = new Map();
  let next = 0;
  const startAll = Date.now();

  async function uploader() {
    while (next < COUNT) {
      const i = next++;
      const form = new FormData();
      form.append("files", new Blob([buf], { type: mime }), `load-${i}.${ext}`);
      form.append("simulateFailures", String(failuresFor(i)));
      const t = Date.now();
      const res = await fetch(`${BASE}/api/jobs`, { method: "POST", headers: auth, body: form });
      if (!res.ok) {
        console.error(`Upload ${i} failed (${res.status})`);
        continue;
      }
      const [job] = await res.json();
      submitAt.set(job.id, t);
    }
  }

  await Promise.all(Array.from({ length: 10 }, uploader));
  const uploadDone = Date.now();
  console.log(`Uploaded ${submitAt.size} jobs in ${((uploadDone - startAll) / 1000).toFixed(1)}s. Waiting for workers...`);

  const doneAt = new Map();
  const final = new Map();
  let peakQueue = 0;
  const deadline = Date.now() + 5 * 60 * 1000;

  while (doneAt.size < submitAt.size && Date.now() < deadline) {
    const now = Date.now();
    const res = await fetch(`${BASE}/api/jobs`, { headers: auth });
    if (res.ok) {
      for (const job of await res.json()) {
        if (!submitAt.has(job.id)) continue;
        final.set(job.id, job);
        if (!doneAt.has(job.id) && (job.status === "COMPLETED" || job.status === "FAILED")) {
          doneAt.set(job.id, now);
        }
      }
    }
    const st = await fetch(`${BASE}/api/admin/stats`, { headers: auth });
    if (st.ok) {
      const s = await st.json();
      peakQueue = Math.max(peakQueue, s.queues.pending + s.queues.delayedRetries);
    }
    await sleep(500);
  }

  const total = (Math.max(...doneAt.values()) - startAll) / 1000;
  const jobs = [...final.values()];
  const completed = jobs.filter((j) => j.status === "COMPLETED").length;
  const failed = jobs.filter((j) => j.status === "FAILED").length;
  const retriedOk = jobs.filter((j) => j.status === "COMPLETED" && j.attempts > 1).length;
  const latencies = [...doneAt.entries()]
    .map(([id, t]) => (t - submitAt.get(id)) / 1000)
    .sort((a, b) => a - b);

  console.log("\n===== QueueForge load test =====");
  console.log(`Jobs submitted:        ${submitAt.size}`);
  console.log(`Finished:              ${doneAt.size}${doneAt.size < submitAt.size ? "  (timed out waiting for the rest)" : ""}`);
  console.log(`Completed:             ${completed}  (${retriedOk} succeeded after retries)`);
  console.log(`Dead-lettered (failed):${failed}`);
  console.log(`Total time:            ${total.toFixed(1)} s`);
  console.log(`Throughput:            ${(doneAt.size / total).toFixed(2)} jobs/s`);
  console.log(`Latency p50 / p95 / max: ${pct(latencies, 50).toFixed(1)} / ${pct(latencies, 95).toFixed(1)} / ${pct(latencies, 100).toFixed(1)} s`);
  console.log(`Peak jobs waiting:     ${peakQueue}`);
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});