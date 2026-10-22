// Grabbit feedback relay: the app posts anonymous feedback / playback errors here; this files GitHub issues.
// The GitHub token (fine-grained, Issues read/write on one repo) lives only in the GRABBIT_GITHUB_TOKEN secret.
import "jsr:@supabase/functions-js/edge-runtime.d.ts";

const REPO = Deno.env.get("GRABBIT_REPO") ?? "thisisshub/grabbit";
const TOKEN = Deno.env.get("GRABBIT_GITHUB_TOKEN") ?? "";
const APP_HEADER = "grabbit-beta"; // not a secret: just keeps stray traffic out

const MOODS: Record<string, string> = { love: "😍", good: "🙂", meh: "😐", sad: "😕", angry: "😤" };
const CATEGORIES: Record<string, { title: string; labels: string[] }> = {
  bug: { title: "Bug", labels: ["bug"] },
  idea: { title: "Idea", labels: ["enhancement"] },
  playback: { title: "Playback", labels: ["bug", "playback"] },
  looks: { title: "Looks", labels: ["area: ui"] },
  other: { title: "Feedback", labels: [] },
};

// Per-install limits, per warm instance (good enough against a stuck loop or a bored friend).
const hits = new Map<string, number[]>();
function allowed(key: string, max: number, windowMs: number): boolean {
  const now = Date.now();
  const list = (hits.get(key) ?? []).filter((t) => now - t < windowMs);
  if (list.length >= max) return false;
  list.push(now);
  hits.set(key, list);
  return true;
}

const clip = (v: unknown, n: number) => (typeof v === "string" ? v.slice(0, n) : "");
// Belt and braces: the app already scrubs, but never let a key or link through.
const scrub = (s: string) =>
  s.replace(/(apikey|api_key|key|token|pass(word)?|user(name)?)=[^&\s"]+/gi, "$1=…")
    .replace(/motn-key-\S+/g, "…").replace(/https?:\/\/\S+/g, "<link>");

async function gh(path: string, init: RequestInit = {}) {
  const r = await fetch(`https://api.github.com${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "grabbit-feedback",
      ...(init.headers ?? {}),
    },
  });
  if (!r.ok) throw new Error(`GitHub ${r.status}: ${(await r.text()).slice(0, 200)}`);
  return r.json();
}

function deviceBlock(b: Record<string, unknown>) {
  return [
    `| | |`, `|---|---|`,
    `| App | ${clip(b.appVersion, 40)} |`,
    `| Device | ${b.isTv ? "Android TV" : "Phone"} · ${clip(b.model, 60)} |`,
    `| Android | ${clip(b.android, 40)} |`,
    `| Decodes | ${clip(b.decodes, 200)} |`,
  ].join("\n");
}

Deno.serve(async (req) => {
  if (req.method !== "POST") return new Response("POST only", { status: 405 });
  if (req.headers.get("x-grabbit-app") !== APP_HEADER) return new Response("nope", { status: 403 });
  if (!TOKEN) return Response.json({ error: "relay not configured" }, { status: 503 });

  let b: Record<string, unknown>;
  try {
    b = await req.json();
  } catch {
    return Response.json({ error: "bad json" }, { status: 400 });
  }
  const install = clip(b.install, 64) || "unknown";
  const deviceLabel = b.isTv ? "tv" : "phone";

  try {
    if (b.kind === "playback") {
      // Automatic and anonymous: only an error class, never a title or release name.
      if (!allowed(`pb:${install}`, 20, 3_600_000)) return Response.json({ ok: true, throttled: true });
      const category = clip(b.category, 60) || "other";
      const code = clip(b.code, 80);
      const fp = `fp-${category}-${code}-${clip(b.appVersion, 30)}`.replace(/[^\w.-]/g, "_");
      const found = await gh(`/search/issues?q=${encodeURIComponent(`repo:${REPO} is:issue is:open in:body "${fp}"`)}`);
      const line = `- ${new Date().toISOString().slice(0, 16).replace("T", " ")} UTC · ${b.isTv ? "TV" : "phone"} · ${clip(b.model, 60)} · Android ${clip(b.android, 20)}`;
      if (found.total_count > 0) {
        const n = found.items[0].number;
        await gh(`/repos/${REPO}/issues/${n}/comments`, { method: "POST", body: JSON.stringify({ body: `Seen again\n${line}` }) });
        return Response.json({ ok: true, issue: n, repeat: true });
      }
      const issue = await gh(`/repos/${REPO}/issues`, {
        method: "POST",
        body: JSON.stringify({
          title: `Auto: playback failed (${category}${code ? `, ${code}` : ""})`,
          labels: ["bug", "playback", "auto-report", "beta", deviceLabel],
          body: `Reported automatically by the app. No title, release or account details are included.\n\n` +
            `**What failed:** ${category}\n**Code:** \`${code || "n/a"}\`\n\n${deviceBlock(b)}\n\nOccurrences:\n${line}\n\n<!-- ${fp} -->`,
        }),
      });
      return Response.json({ ok: true, issue: issue.number });
    }

    // Hand-written feedback form.
    if (!allowed(`fb:${install}`, 6, 3_600_000)) return Response.json({ error: "slow down a little 🐇" }, { status: 429 });
    const message = scrub(clip(b.message, 2000)).trim();
    if (message.length < 3) return Response.json({ error: "say a little more" }, { status: 400 });
    const cat = CATEGORIES[clip(b.category, 20)] ?? CATEGORIES.other;
    const mood = MOODS[clip(b.mood, 10)] ?? "";
    const firstLine = message.split("\n")[0].slice(0, 70);
    const issue = await gh(`/repos/${REPO}/issues`, {
      method: "POST",
      body: JSON.stringify({
        title: `${cat.title}: ${firstLine}${firstLine.length < message.split("\n")[0].length ? "…" : ""}`,
        labels: [...cat.labels, "beta", "feedback", deviceLabel],
        body: `${mood ? `Mood: ${mood}\n\n` : ""}${message.split("\n").map((l) => `> ${l}`).join("\n")}\n\n` +
          `${deviceBlock(b)}\n\n<sub>Sent anonymously from the app.</sub>`,
      }),
    });
    return Response.json({ ok: true, issue: issue.number });
  } catch (e) {
    console.error(e);
    return Response.json({ error: "couldn't reach GitHub" }, { status: 502 });
  }
});
