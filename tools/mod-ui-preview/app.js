/* ============================================================
   菀星设计总局 · 模组总 UI 预览原型 — 交互与动效逻辑
   动效清单：终端开机 / 逐行打印 / 盖章 / 回执打印 / 闪现 glitch / 待机呼吸
   ============================================================ */
"use strict";

/* ---------------- 模拟数据 ---------------- */

// 状态：pending 待接取 / active 执行中 / done 已核销
const STATUS_LABEL = { pending: "待接取", active: "执行中", done: "已核销" };

const CLAUSE_NOTE =
  "系统提示：目标威胁评估已于你方出击期间更新。相关条款已按章程追加至后续工单。" +
  "由此产生的风险增量，已计入报酬调整。特此告知。";

const ORDERS = [
  {
    batch: "批次一 · 例行核销",
    items: [
      {
        id: "XW-c206-0447／核销-03",
        level: "Ⅰ",
        summary: "注销违约资产「总局旧制式巡洋舰 Ⅶ-未编号」",
        status: "done",
        reward: 462000,
        doc: [
          { t: "委托事项：注销违约资产「总局旧制式巡洋舰 Ⅶ-未编号」一艘，回收其航行数据柜（铅封），送至本分局核销台办理交割。" },
          { t: "签发日期：c+206 年第 3 审计周期" },
          { t: "履约期限：签发后 90 标准日（已逾期约两个世纪）" },
          { t: "违约金：按章程第 11 条复利计——" },
          { t: "999,999,999,999,999,999,999,999,999,999…（数值溢出）", cls: "dim" },
          { t: "依第 11 条之七，委托方灭失情形下的逾期违约金予以豁免。特此说明。", anno: true },
          { t: "危险等级：一级（最低）" },
          { t: "备注：本单自签发以来无任何承包商应标。你是第一位。欢迎。" },
        ],
      },
      {
        id: "XW-c206-0512／核销-11",
        level: "Ⅱ",
        summary: "注销海盗头目「断链者」及其护卫船团",
        status: "active",
        reward: 388000,
        doc: [
          { t: "委托事项：注销违约资产——海盗头目「断链者」旗舰一艘及其随行护卫船团，回收其识别信标（铅封），送至本分局核销台办理交割。" },
          { t: "签发日期：c+206 年第 3 审计周期" },
          { t: "履约期限：签发后 120 标准日（已逾期约两个世纪）" },
          { t: "违约金：已依第 11 条之七豁免。特此说明。", anno: true },
          { t: "危险等级：二级" },
          { t: "备注：本单属常规积压件，无特殊条款。" },
        ],
      },
    ],
  },
  {
    batch: "批次二 · 条款追加",
    items: [
      {
        id: "XW-c206-0588／核销-14",
        level: "Ⅱ",
        summary: "注销违约船团「灰市联合体」走私分队",
        status: "pending",
        reward: 540000,
        affixes: ["【词缀·备弹充裕】"],
        doc: [
          { t: "委托事项：注销违约船团「灰市联合体」所属走私分队，回收其货运清单数据柜（铅封），送至本分局核销台办理交割。" },
          { t: "签发日期：c+206 年第 3 审计周期" },
          { t: "履约期限：签发后 120 标准日（已逾期约两个世纪）" },
          { t: "危险等级：二级" },
          { t: "追加条款：目标携带词缀【备弹充裕】。", anno: true },
          { t: CLAUSE_NOTE, anno: true },
          { t: "备注：本单属常规积压件，条款以系统最新批注为准。" },
        ],
      },
      {
        id: "XW-c206-0591／核销-15",
        level: "Ⅲ",
        summary: "注销违约资产「改装战列舰·残账」",
        status: "pending",
        reward: 720000,
        affixes: ["【词缀·装甲强化】"],
        doc: [
          { t: "委托事项：注销违约资产「改装战列舰·残账」一艘，回收其改装许可档案（铅封），送至本分局核销台办理交割。" },
          { t: "签发日期：c+206 年第 3 审计周期" },
          { t: "履约期限：签发后 150 标准日（已逾期约两个世纪）" },
          { t: "危险等级：三级" },
          { t: "追加条款：目标携带词缀【装甲强化】，并伴随少量主力舰护航。", anno: true },
          { t: CLAUSE_NOTE, anno: true },
          { t: "备注：本单属常规积压件，条款以系统最新批注为准。" },
        ],
      },
      {
        id: "XW-c206-0596／核销-16",
        level: "Ⅲ",
        summary: "注销违约船团「空白旗」游击编队",
        status: "pending",
        reward: 815000,
        affixes: ["【词缀·电子战】", "【词缀·死战不退】"],
        doc: [
          { t: "委托事项：注销违约船团「空白旗」所属游击编队，回收其指挥链数据柜（铅封），送至本分局核销台办理交割。" },
          { t: "签发日期：c+206 年第 3 审计周期" },
          { t: "履约期限：签发后 150 标准日（已逾期约两个世纪）" },
          { t: "危险等级：三级" },
          { t: "追加条款：目标携带词缀【电子战】【死战不退】。", anno: true },
          { t: CLAUSE_NOTE, anno: true },
          { t: "备注：本单属常规积压件，条款以系统最新批注为准。" },
        ],
      },
    ],
  },
  {
    batch: "批次三 · 加急件",
    items: [
      {
        id: "XW-c206-0601／核销-19【加急】",
        level: "Ⅳ",
        summary: "注销「余晖」巡察战斗群（加急）",
        status: "pending",
        reward: 1350000,
        urgent: true,
        doc: [
          { t: "委托事项：注销违约资产——「余晖」巡察战斗群一支，回收其核心逻辑封存柜（铅封），送至本分局核销台办理交割。" },
          { t: "签发日期：c+14 年第 7 审计周期（距今约两个世纪）" },
          { t: "履约期限：签发后 30 标准日（【加急】戳记于签发当日加盖）" },
          { t: "危险等级：四级" },
          { t: "追加条款：目标携带复数词缀组合，舰队规模上调。条款明细以核销台回执为准。", anno: true },
          { t: "备注：请优先处理。" },
          { t: "（备注栏签发日期为两个世纪以前。）", cls: "dim" },
        ],
      },
    ],
  },
];

const ARCHIVES = [
  {
    title: "《总局组织章程（节选）》",
    unlocked: true,
    body:
      "第一条 总局设立之宗旨，在于签发、裁决、记录。\n" +
      "第二条 总局不统治、不复兴、不倡议。总局只受理委托。\n" +
      "第三条 一切资产皆有账期。一切账期皆有结清之日。结清之日未至者，总局等。\n\n" +
      "（章程全篇通检：未出现「统治」「复兴」字样。——档案员注）",
  },
  {
    title: "《星坠理事会与紫菀理事会分工备忘录》",
    unlocked: true,
    body:
      "星坠理事会主研打击与投射，紫菀理事会主研观测与演算。\n" +
      "两院同源同址、业务分明、互不隶属、互相验收。\n\n" +
      "备忘录附言：星坠造矛，紫菀磨镜。矛不指镜，镜不评矛。\n" +
      "凡涉两院协作之订单，由总局居中签发，验收各半。",
  },
  {
    title: "《与速子科技竞合关系年度通报（第 187 期）》",
    unlocked: true,
    body:
      "本审计周期内，两院与速子科技就同一批人之领订单展开竞标，共计十七项。\n" +
      "我方中标九项，速子中标八项。\n\n" +
      "通报措辞如常克制。惟第 4 段标点密度异常，句号连用三处。\n" +
      "（档案员按：火药味从标点里渗出来。）",
  },
  { title: "《分局资产年检报告（连续第 203 期）》", unlocked: false },
  {
    title: "《外勤协调员管理条例》",
    unlocked: true,
    body:
      "第三条 着装：总局制式外勤服装，两百年款，不得混穿。\n" +
      "第七条 欠身角度：十五度，不多不少。\n" +
      "第十二条 话术规程：全程公文规程体，礼貌得无懈可击。\n" +
      "第十九条 外勤协调员不做任何超自然表现。他不是鬼，也不是全息把戏——他是一个「界面」。\n\n" +
      "（读者按：序章酒馆里的每一个细节，都能在本条例里找到条目编号。）",
  },
  { title: "《战斗群保障条例（存目）》", unlocked: false },
  {
    title: "《承包商管理办法》",
    unlocked: true,
    body:
      "乙方义务：共计四十七条。摘录如下——\n" +
      "第一条 乙方应按期交割。\n" +
      "第二条 乙方不得拆阅铅封数据柜。\n" +
      "……（中略四十四条）……\n" +
      "第四十七条 本办法解释权归甲方所有。\n\n" +
      "甲方义务：共计三条。\n" +
      "第一条 甲方应准时、足额支付报酬。\n" +
      "第二条 甲方应附明细单。\n" +
      "第三条 甲方应当等待。",
  },
];

const LEDGER = [
  { id: "XW-c206-0447／核销-03", date: "c+206.3 · 第 41 日", debit: "", credit: "462,000", note: "首单核销报酬，按期交割。" },
  { id: "批次一 · 结清奖金", date: "c+206.3 · 第 58 日", debit: "", credit: "300,000", note: "批次一全部核销，予以结清。" },
  { id: "弹药与补给调拨", date: "c+206.3 · 第 60 日", debit: "18,500", credit: "", note: "分局货栈调拨，挂账扣除。" },
  { id: "泊位占用费", date: "c+206.3 · 第 61 日", debit: "3,200", credit: "", note: "按标准泊位列费率计。" },
  { id: "违约金豁免批注", date: "c+206.3 · 第 41 日", debit: "0", credit: "0", note: "依第 11 条之七豁免，不计入评价。" },
];

const CONTRACTOR = {
  id: "CT-c206-0001",
  rank: "一级",
  registered: "c+206 年第 3 审计周期",
  note: "登记簿上两百年来的第一行新墨迹。",
};

/* ---------------- 工具 ---------------- */

const $ = (sel) => document.querySelector(sel);

function el(tag, cls, text) {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
}

function fmtNum(n) {
  return n.toLocaleString("en-US");
}

/* 简易打印音（WebAudio 合成，无外部素材） */
let audioCtx = null;
function beep(freq = 1900, dur = 0.015, gain = 0.02) {
  try {
    if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    if (audioCtx.state === "suspended") audioCtx.resume();
    const o = audioCtx.createOscillator();
    const g = audioCtx.createGain();
    o.type = "square";
    o.frequency.value = freq;
    g.gain.value = gain;
    g.gain.exponentialRampToValueAtTime(0.0001, audioCtx.currentTime + dur);
    o.connect(g).connect(audioCtx.destination);
    o.start();
    o.stop(audioCtx.currentTime + dur);
  } catch (e) { /* 无声环境忽略 */ }
}

/* 逐行打印：将 lineSpecs 逐行、行内逐字打出 */
let printToken = 0;
function printLines(container, lineSpecs, onDone) {
  const token = ++printToken;
  container.innerHTML = "";
  let li = 0;

  function nextLine() {
    if (token !== printToken) return;
    if (li >= lineSpecs.length) {
      if (onDone) onDone();
      return;
    }
    const spec = lineSpecs[li++];
    const line = el("div", "doc-line" + (spec.cls ? " " + spec.cls : "") + (spec.anno ? " doc-anno" : ""));
    container.appendChild(line);
    // 触发打印音
    beep(1700 + Math.random() * 600, 0.012, 0.015);

    const text = spec.t;
    let ci = 0;
    const step = Math.max(1, Math.floor(text.length / 40)); // 长行加速
    function typeChar() {
      if (token !== printToken) return;
      ci = Math.min(text.length, ci + step);
      line.textContent = text.slice(0, ci);
      if (ci < text.length) {
        setTimeout(typeChar, 12);
      } else {
        setTimeout(nextLine, 45);
      }
    }
    typeChar();
  }
  nextLine();
}

/* ---------------- 状态 ---------------- */

let currentTab = "orders";
let selectedOrder = ORDERS[0].items[0];
let selectedArchive = ARCHIVES[0];
let glitchBusy = false;

/* ---------------- Tab 切换 ---------------- */

const TAB_LIST_TITLE = {
  orders: "工单列表 · 按批次分组",
  archive: "档案室 · 第一层",
  account: "履约流水账",
};

function switchTab(tab) {
  currentTab = tab;
  document.querySelectorAll(".tab").forEach((b) => b.classList.toggle("active", b.dataset.tab === tab));
  $("#list-header").textContent = TAB_LIST_TITLE[tab];
  $("#glitch-demo-btn").classList.toggle("hidden", tab !== "orders");
  beep(2400, 0.03, 0.03);
  if (tab === "orders") renderOrders();
  else if (tab === "archive") renderArchive();
  else renderAccount();
}

/* ---------------- Tab 1：工单终端 ---------------- */

function batchProgress(batch) {
  const done = batch.items.filter((o) => o.status === "done").length;
  return done + "/" + batch.items.length;
}

function renderOrders() {
  const list = $("#list-body");
  list.innerHTML = "";
  ORDERS.forEach((batch) => {
    const head = el("div", "batch-head");
    head.appendChild(el("span", null, batch.batch));
    head.appendChild(el("span", "batch-progress", "结清 " + batchProgress(batch)));
    list.appendChild(head);
    batch.items.forEach((o) => {
      const row = el("div", "list-row" + (o === selectedOrder ? " selected" : ""));
      row.dataset.orderId = o.id;
      row.appendChild(el("span", "row-id", o.id));
      row.appendChild(el("span", "row-level", o.level));
      row.appendChild(el("span", "row-summary", o.summary));
      const st = el("span", "row-status st-" + o.status, STATUS_LABEL[o.status]);
      row.appendChild(st);
      row.addEventListener("click", () => {
        selectedOrder = o;
        beep(2100, 0.015, 0.02);
        renderOrders();
      });
      list.appendChild(row);
    });
  });
  renderOrderDetail();
  renderOrderActions();
}

function renderOrderDetail() {
  const o = selectedOrder;
  $("#detail-title").textContent = "文书 · " + o.id;
  const body = $("#detail-body");
  body.innerHTML = "";

  const card = el("div", "paper-card");
  card.id = "paper-card";
  card.appendChild(el("div", "doc-head", "菀星设计总局 · 英仙座第七分局 · 签发"));
  card.appendChild(el("div", "doc-seam", "编号：" + o.id + "　‖　编号骑缝　‖　危险等级：" + o.level));

  const seal = el("div", "lead-seal");
  seal.innerHTML = "铅封<br>英七分局";
  card.appendChild(seal);

  // 状态对应的常驻印章（盖章动效结束后由它保持观感）
  if (o.status !== "pending") {
    const staticStamp = el("div", "stamp ink", o.status === "done" ? "已核销" : "已受理");
    staticStamp.style.right = "36px";
    staticStamp.style.bottom = "42px";
    staticStamp.style.opacity = "0.85";
    card.appendChild(staticStamp);
  }

  const linesBox = el("div");
  card.appendChild(linesBox);

  // 报酬行（橙黄数值观感在纸卡上用深橙）
  const docLines = o.doc.concat([
    { t: "报酬：星币 " + fmtNum(o.reward) + "（核销交割后一次性支付，附明细单）" },
    { t: o.urgent ? "——本件为加急文书。【加急】戳记见右上角铅封旁。" : "——本文书由分局行政系统自动生成，无需回复。", cls: "dim" },
  ]);

  body.appendChild(card);
  printLines(linesBox, docLines);
}

function renderOrderActions() {
  const area = $("#action-area");
  area.innerHTML = "";
  const o = selectedOrder;
  if (o.status === "pending") {
    const b = el("button", "btn btn-primary", "接取工单");
    b.addEventListener("click", () => acceptOrder(o));
    area.appendChild(b);
  } else if (o.status === "active") {
    const b = el("button", "btn btn-primary", "前往核销");
    b.addEventListener("click", () => settleOrder(o));
    area.appendChild(b);
  } else {
    const b = el("button", "btn", "已核销 · 无需操作");
    b.disabled = true;
    area.appendChild(b);
    const r = el("button", "btn", "重打回执");
    r.addEventListener("click", () => showReceipt(o));
    area.appendChild(r);
  }
}

/* 盖章动效：红色印章砸在文书角上 + 屏幕震动 + 墨渍扩散 */
function slamStamp(text, after) {
  const card = $("#paper-card");
  if (!card) return;
  beep(120, 0.08, 0.06);

  // 先移除旧状态章，避免与新章叠影
  card.querySelectorAll(".stamp").forEach((s) => s.remove());

  const stamp = el("div", "stamp ink", text);
  stamp.style.right = "36px";
  stamp.style.bottom = "42px";
  stamp.style.transform = "rotate(-14deg) scale(2.6)";
  stamp.style.opacity = "0";
  card.appendChild(stamp);

  const splash = el("div", "stamp-splash");
  splash.style.right = "8px";
  splash.style.bottom = "14px";
  splash.style.width = "60px";
  splash.style.height = "60px";
  splash.style.opacity = "0";
  card.appendChild(splash);

  // 砸落
  requestAnimationFrame(() => {
    stamp.style.transition = "transform 0.12s cubic-bezier(.6,0,1,.6), opacity 0.1s";
    stamp.style.transform = "rotate(-14deg) scale(1)";
    stamp.style.opacity = "0.92";
  });

  // 震动 + 墨渍扩散
  setTimeout(() => {
    const stage = $("#stage");
    stage.classList.remove("shake");
    void stage.offsetWidth;
    stage.classList.add("shake");
    splash.style.transition = "transform 0.6s ease-out, opacity 0.6s ease-out";
    splash.style.transform = "scale(3.2)";
    splash.style.opacity = "0.5";
    beep(90, 0.1, 0.05);
  }, 130);

  setTimeout(() => {
    stamp.style.transition = "opacity 0.4s";
    splash.style.opacity = "0.25";
    if (after) after();
  }, 700);
}

function acceptOrder(o) {
  slamStamp("已受理", () => {
    o.status = "active";
    renderOrders();
  });
}

function settleOrder(o) {
  slamStamp("已核销", () => {
    o.status = "done";
    renderOrders();
    showReceipt(o);
  });
}

/* ---------------- 回执打印 ---------------- */

function showReceipt(o) {
  const layer = $("#receipt-layer");
  const body = $("#receipt-body");
  layer.classList.remove("hidden");
  requestAnimationFrame(() => $("#receipt").classList.add("shown"));
  beep(1500, 0.05, 0.03);

  const lines = [
    { t: "工单编号：" + o.id },
    { t: "委托事项：" + o.summary },
    { t: "交割日期：c+206 年第 3 审计周期 · 当日" },
    { t: "条款核验：通过　　铅封数据柜：已接收　　逾期违约金：依第 11 条之七豁免" },
    { t: "报酬合计：星币 ", amount: o.reward },
  ];

  body.innerHTML = "";
  let li = 0;
  function nextLine() {
    if (li >= lines.length) return;
    const spec = lines[li++];
    const line = el("div", "r-line" + (spec.cls ? " " + spec.cls : ""));
    body.appendChild(line);
    beep(1700 + Math.random() * 500, 0.012, 0.015);
    if (spec.amount != null) {
      // 金额数字滚动到位
      line.textContent = spec.t;
      const num = el("span", "num", "0");
      line.appendChild(num);
      const target = spec.amount;
      const t0 = performance.now();
      const dur = 900;
      (function roll(now) {
        const p = Math.min(1, (now - t0) / dur);
        const eased = 1 - Math.pow(1 - p, 3);
        num.textContent = fmtNum(Math.round(target * eased));
        if (p < 1) requestAnimationFrame(roll);
        else setTimeout(nextLine, 60);
      })(t0);
    } else {
      const text = spec.t;
      let ci = 0;
      const step = Math.max(1, Math.floor(text.length / 30));
      (function typeChar() {
        ci = Math.min(text.length, ci + step);
        line.textContent = text.slice(0, ci);
        if (ci < text.length) setTimeout(typeChar, 12);
        else setTimeout(nextLine, 50);
      })();
    }
  }
  nextLine();
}

$("#receipt-close").addEventListener("click", () => {
  $("#receipt").classList.remove("shown");
  setTimeout(() => $("#receipt-layer").classList.add("hidden"), 350);
});

/* ---------------- Tab 2：档案室 ---------------- */

function renderArchive() {
  const list = $("#list-body");
  list.innerHTML = "";
  const head = el("div", "batch-head");
  head.appendChild(el("span", null, "第一层 · 总局日常"));
  head.appendChild(el("span", "batch-progress", ARCHIVES.filter((a) => a.unlocked).length + "/" + ARCHIVES.length + " 已解锁"));
  list.appendChild(head);

  ARCHIVES.forEach((a, i) => {
    const row = el("div", "list-row" + (a.unlocked ? "" : " locked") + (a === selectedArchive ? " selected" : ""));
    row.appendChild(el("span", "row-id", "档-" + String(i + 1).padStart(2, "0")));
    row.appendChild(el("span", "row-summary", a.title));
    row.appendChild(el("span", "row-status " + (a.unlocked ? "st-active" : "st-done"), a.unlocked ? "可阅读" : "存目"));
    if (a.unlocked) {
      row.addEventListener("click", () => {
        selectedArchive = a;
        beep(2100, 0.015, 0.02);
        renderArchive();
      });
    }
    list.appendChild(row);
  });

  renderArchiveDetail();
  $("#action-area").innerHTML = "";
}

function renderArchiveDetail() {
  const a = selectedArchive;
  $("#detail-title").textContent = "阅读器 · " + a.title;
  const body = $("#detail-body");
  body.innerHTML = "";

  if (!a.unlocked) {
    const note = el("div", "archive-locked-note", "依保密条令不予展示。");
    body.appendChild(note);
    return;
  }

  const doc = el("div", "archive-doc");
  doc.appendChild(el("div", "ad-title", a.title));
  const meta = el("div", "ad-meta");
  meta.innerHTML = "调阅编号：AR-Ⅶ-1-" + String(ARCHIVES.indexOf(a) + 1).padStart(3, "0") +
    "　·　调阅时间：<span class='ad-num'>c+206 · 第 3 审计周期</span>　·　载体：旧打印件";
  doc.appendChild(meta);
  const content = el("div");
  doc.appendChild(content);
  body.appendChild(doc);

  printLines(content, a.body.split("\n").map((t) => ({ t: t || "　" })));
}

/* ---------------- Tab 3：承包商账户 ---------------- */

function renderAccount() {
  $("#detail-title").textContent = "账户明细 · " + CONTRACTOR.id;
  const list = $("#list-body");
  list.innerHTML = "";

  // 左栏放流水条目索引
  const head = el("div", "batch-head");
  head.appendChild(el("span", null, "本审计周期 · 流水"));
  head.appendChild(el("span", "batch-progress", LEDGER.length + " 笔"));
  list.appendChild(head);
  LEDGER.forEach((l) => {
    const row = el("div", "list-row");
    row.style.cursor = "default";
    row.appendChild(el("span", "row-id", l.id));
    row.appendChild(el("span", "row-summary", l.note));
    list.appendChild(row);
  });

  // 右栏：账户头 + 账本
  const body = $("#detail-body");
  body.innerHTML = "";

  const headBox = el("div", "account-head");
  const badge = el("div", "rank-badge", CONTRACTOR.rank);
  headBox.appendChild(badge);
  const fields = [
    ["承包商编号", CONTRACTOR.id, true],
    ["承包商等级", CONTRACTOR.rank + "（可申领范围：批次一 ~ 批次三）", false],
    ["注册日期", CONTRACTOR.registered, false],
    ["登记批注", CONTRACTOR.note, false],
  ];
  fields.forEach(([label, value, isNum]) => {
    const f = el("div");
    f.appendChild(el("span", "acc-label", label));
    f.appendChild(el("span", "acc-value" + (isNum ? " num" : ""), value));
    headBox.appendChild(f);
  });
  body.appendChild(headBox);

  const table = el("table", "ledger");
  const thead = el("tr");
  ["编号", "交割日期", "借（支出）", "贷（收入）", "批注"].forEach((h) => thead.appendChild(el("th", null, h)));
  table.appendChild(thead);
  LEDGER.forEach((l) => {
    const tr = el("tr");
    tr.appendChild(el("td", "l-id", l.id));
    tr.appendChild(el("td", "l-date", l.date));
    tr.appendChild(el("td", "l-debit", l.debit));
    tr.appendChild(el("td", "l-credit", l.credit));
    tr.appendChild(el("td", "l-note", l.note));
    table.appendChild(tr);
  });
  const totalCredit = LEDGER.reduce((s, l) => s + (l.credit ? parseInt(l.credit.replace(/,/g, "")) : 0), 0);
  const totalDebit = LEDGER.reduce((s, l) => s + (l.debit ? parseInt(l.debit.replace(/,/g, "")) : 0), 0);
  const tr = el("tr", "l-total");
  tr.appendChild(el("td", "l-id", "本周期结余"));
  tr.appendChild(el("td", "l-date", "—"));
  tr.appendChild(el("td", "l-debit", fmtNum(totalDebit)));
  tr.appendChild(el("td", "l-credit", fmtNum(totalCredit)));
  tr.appendChild(el("td", "l-note", "净额：星币 " + fmtNum(totalCredit - totalDebit)));
  table.appendChild(tr);
  body.appendChild(table);

  $("#action-area").innerHTML = "";
}

/* ---------------- 闪现 glitch ---------------- */

function demoGlitch() {
  if (glitchBusy) return;
  glitchBusy = true;
  beep(70, 0.12, 0.05);

  // 找到当前选中工单在列表里的状态栏
  const row = document.querySelector('.list-row[data-order-id="' + selectedOrder.id + '"] .row-status');
  $("#glitch-noise").classList.remove("hidden");
  if (row) {
    row.dataset.orig = row.textContent;
    row.textContent = "目标状态：现役？";
    row.classList.add("glitching");
  }
  // 文书备注行同步撕裂
  const card = $("#paper-card");
  if (card) card.classList.add("glitching");

  setTimeout(() => {
    // 自愈，系统不解释
    if (row) {
      row.textContent = row.dataset.orig;
      row.classList.remove("glitching");
    }
    if (card) card.classList.remove("glitching");
    $("#glitch-noise").classList.add("hidden");
    glitchBusy = false;
  }, 450);
}

$("#glitch-demo-btn").addEventListener("click", demoGlitch);

/* ---------------- 终端开机 ---------------- */

let booted = false;
function playBoot() {
  const layer = $("#boot-layer");
  layer.classList.remove("hidden", "fading");
  layer.style.opacity = "";
  const sweep = $("#boot-sweep");
  const emblem = $("#boot-emblem");
  sweep.classList.remove("sweeping");
  emblem.classList.remove("shown");
  booted = false;

  const tabs = Array.from(document.querySelectorAll(".tab"));
  const tabTexts = tabs.map((t) => t.textContent);
  tabs.forEach((t) => (t.textContent = ""));

  let skipTimer = [];
  function finish() {
    if (booted) return;
    booted = true;
    skipTimer.forEach(clearTimeout);
    tabs.forEach((t, i) => (t.textContent = tabTexts[i]));
    layer.classList.add("fading");
    setTimeout(() => layer.classList.add("hidden"), 300);
    switchTab("orders");
  }
  layer.onclick = finish;

  // 0.05s：扫描线自上而下点亮（0.55s）
  skipTimer.push(setTimeout(() => {
    sweep.classList.add("sweeping");
    beep(300, 0.2, 0.02);
  }, 50));
  // 0.6s：总局徽记淡入
  skipTimer.push(setTimeout(() => {
    emblem.classList.add("shown");
    beep(900, 0.1, 0.02);
  }, 600));
  // 0.9s：tab 栏逐字打出
  skipTimer.push(setTimeout(() => {
    tabs.forEach((t, i) => {
      const txt = tabTexts[i];
      let ci = 0;
      (function typeTab() {
        if (booted) return;
        ci++;
        t.textContent = txt.slice(0, ci);
        beep(2200, 0.012, 0.012);
        if (ci < txt.length) setTimeout(typeTab, 40);
      })();
    });
  }, 900));
  // 约 1.2s+打字余量：自动进入
  skipTimer.push(setTimeout(finish, 1500));
}

/* ---------------- 关闭终端 ---------------- */

function closeTerminal() {
  beep(500, 0.08, 0.03);
  $("#closed-layer").classList.remove("hidden");
}
$("#btn-close").addEventListener("click", closeTerminal);
$("#btn-reboot").addEventListener("click", () => {
  $("#closed-layer").classList.add("hidden");
  playBoot();
});
document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && booted && $("#closed-layer").classList.contains("hidden")) {
    closeTerminal();
  }
});

/* ---------------- 启动 ---------------- */

document.querySelectorAll(".tab").forEach((b) => b.addEventListener("click", () => switchTab(b.dataset.tab)));
playBoot();
