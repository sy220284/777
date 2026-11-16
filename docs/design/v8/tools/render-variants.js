// 为 V8 离线稿生成三种风格/配色变体并排对比图。
// 用法：node variants.js <app.html 绝对路径> <输出目录>
const puppeteer = require('puppeteer');
const path = require('path');
const fs = require('fs');

const APP = process.argv[2];
const OUT = process.argv[3] || path.join(__dirname, 'variants');
const EXEC = '/root/.cache/puppeteer/chrome-headless-shell/linux_arm-157.0.8103.78/chrome-headless-shell-linux-arm64/chrome-headless-shell';

const VARIANTS = {
  A: {
    name: '夜航 · 靛蓝',
    theme: 'dark',
    css: `
      body[data-theme="dark"]{
        --canvas:#0E1016; --surface:#141822; --raised:#1B2029;
        --ink:#E8ECF4; --ink2:#A7B0C2; --ink3:#7F8799;
        --rule:#232936; --ruleStrong:#333A4A;
        --accent:#5B8CFF; --accentPressed:#3F73E8; --onAccent:#08101F; --accentSoft:#17233D;
        --success:#4FD1A5; --warning:#E8B84B; --danger:#FF6B6B;
        --shadow-card:0 1px 0 rgba(255,255,255,.04),0 8px 22px -14px rgba(0,0,0,.85);
        --shadow-dock:0 1px 0 rgba(255,255,255,.05),0 16px 36px -18px rgba(0,0,0,.9);
        --shadow-overlay:0 1px 0 rgba(255,255,255,.06),0 24px 56px -24px rgba(0,0,0,.95);
        --shadow-menu:0 1px 0 rgba(255,255,255,.05),0 18px 40px -20px rgba(0,0,0,.9);
        --radius-sm:8px; --radius-control:10px; --radius-card:14px; --radius-sheet:18px; --radius-overlay:12px;
        --fs-body:14px; --lh-body:23px; --fs-bodyStrong:14px; --lh-bodyStrong:21px;
        --fs-title:19px; --lh-title:25px;
      }
      .app::before{background:radial-gradient(110% 55% at 12% -8%,rgba(91,140,255,.16),transparent 60%),radial-gradient(90% 50% at 100% 104%,rgba(79,209,165,.08),transparent 62%)!important}
      .app::after{opacity:0!important}
      .card,.step-card,.dock,.person,.artifact{border-color:transparent!important}
      .rows,.row{border-color:var(--rule)!important}
      .reply blockquote{border-left-color:var(--accent)!important}
      .seal{background:var(--accent)!important;color:var(--onAccent)!important;box-shadow:0 6px 16px -8px rgba(91,140,255,.9)!important;border-radius:8px!important}
    `,
  },
  B: {
    name: '青瓷 · 苔绿',
    theme: 'light',
    css: `
      body[data-theme="light"]{
        --canvas:#EDF1EC; --surface:#F6F8F4; --raised:#FFFFFF;
        --ink:#1F2A26; --ink2:#4E5C56; --ink3:#617069;
        --rule:#DCE4DC; --ruleStrong:#C3CFC7;
        --accent:#2F7D62; --accentPressed:#256550; --onAccent:#F2FAF6; --accentSoft:#DCEBE3;
        --success:#2F7D62; --warning:#9A6A18; --danger:#B23A2F;
        --shadow-card:0 2px 4px rgba(31,52,44,.05),0 14px 30px -18px rgba(31,52,44,.26);
        --shadow-dock:0 3px 8px rgba(31,52,44,.07),0 22px 44px -22px rgba(31,52,44,.32);
        --shadow-overlay:0 10px 26px -8px rgba(31,52,44,.22),0 32px 64px -30px rgba(31,52,44,.4);
        --shadow-menu:0 8px 20px -6px rgba(31,52,44,.2),0 24px 48px -24px rgba(31,52,44,.34);
        --radius-sm:14px; --radius-control:18px; --radius-card:24px; --radius-sheet:30px; --radius-overlay:22px;
        --fs-body:15px; --lh-body:25px;
      }
      .app::before{background:radial-gradient(120% 60% at 50% -10%,rgba(47,125,98,.07),transparent 62%),radial-gradient(90% 50% at 100% 104%,rgba(180,64,28,.04),transparent 60%)!important}
      .app::after{opacity:.03!important}
      .row{min-height:60px}
      .person{border-width:0!important}
      .person[aria-pressed="true"]{box-shadow:0 0 0 2px var(--accent),var(--shadow-card)!important}
      .seal{border-radius:14px!important;box-shadow:0 8px 18px -8px rgba(47,125,98,.9)!important}
      .bubble{border-radius:18px!important}
    `,
  },
  C: {
    name: '绯夜 · 品红',
    theme: 'dark',
    css: `
      body[data-theme="dark"]{
        --canvas:#17101A; --surface:#1F1624; --raised:#281B2F;
        --ink:#F6EDF7; --ink2:#C4B2CB; --ink3:#93819B;
        --rule:#33243C; --ruleStrong:#463354;
        --accent:#FF5C8A; --accentPressed:#E04472; --onAccent:#2A0715; --accentSoft:#3D1E31;
        --success:#5AD6A8; --warning:#F0B24B; --danger:#FF7A7A;
        --shadow-card:0 1px 0 rgba(255,255,255,.05),0 12px 30px -16px rgba(0,0,0,.8),0 0 22px -14px rgba(255,92,138,.5);
        --shadow-dock:0 1px 0 rgba(255,255,255,.06),0 18px 40px -20px rgba(0,0,0,.85),0 0 26px -16px rgba(255,92,138,.45);
        --shadow-overlay:0 1px 0 rgba(255,255,255,.07),0 28px 60px -26px rgba(0,0,0,.9),0 0 34px -20px rgba(255,92,138,.5);
        --shadow-menu:0 1px 0 rgba(255,255,255,.06),0 20px 44px -22px rgba(0,0,0,.85);
        --radius-sm:12px; --radius-control:16px; --radius-card:22px; --radius-sheet:30px; --radius-overlay:20px;
      }
      .app::before{background:radial-gradient(120% 62% at 50% -12%,rgba(255,92,138,.20),transparent 62%),radial-gradient(90% 55% at 4% 104%,rgba(90,214,168,.10),transparent 62%)!important}
      .app::after{opacity:.045!important}
      .card,.step-card,.person,.artifact{backdrop-filter:blur(18px);background:rgba(40,27,47,.72)!important}
      .dock{backdrop-filter:blur(22px);background:rgba(40,27,47,.78)!important}
      .sheet,.menu,.dialog{backdrop-filter:blur(26px)}
      .seal{box-shadow:0 8px 20px -8px rgba(255,92,138,.9)!important}
    `,
  },
};

const SHOTS = [
  { key: 'chat', q: 'route=HOME' },
  { key: 'work', q: 'route=HOME&mode=WORK' },
  { key: 'settings', q: 'route=SETTINGS' },
];

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: EXEC,
    headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--allow-file-access-from-files'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 393, height: 852, deviceScaleFactor: 2 });

  const cards = [];
  for (const [key, v] of Object.entries(VARIANTS)) {
    for (const shot of SHOTS) {
      await page.goto('file://' + APP + '?' + shot.q + '&theme=' + v.theme, { waitUntil: 'networkidle0' });
      await page.addStyleTag({ content: v.css });
      await new Promise(r => setTimeout(r, 300));
      const app = await page.$('#app');
      const file = path.join(OUT, `${key}-${shot.key}.png`);
      await app.screenshot({ path: file });
      console.log('shot', key, shot.key);
    }
    cards.push({ key, name: v.name, chat: path.join(OUT, `${key}-chat.png`), work: path.join(OUT, `${key}-work.png`), settings: path.join(OUT, `${key}-settings.png`) });
  }

  // 并排对比页：三列，每列三屏
  const sheet = `<!doctype html><meta charset="utf-8"><style>
    body{margin:0;background:#12100F;font-family:-apple-system,"Noto Sans SC",sans-serif;padding:28px}
    .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:26px}
    .col{background:#1B1817;border:1px solid #2C2725;border-radius:20px;padding:16px}
    .name{color:#F0E9E1;font-size:20px;font-weight:700;margin:0 0 4px}
    .desc{color:#9C9189;font-size:13px;margin:0 0 14px;line-height:1.6}
    .row2{display:flex;gap:10px}
    .row2 img{width:100%;border-radius:10px;border:1px solid #322C2A}
    .lab{color:#8A7F77;font-size:11px;margin:6px 0 4px;letter-spacing:.08em}
  </style><div class="grid">
  ${cards.map(c => `<div class="col">
    <p class="name">${c.name}</p>
    <p class="desc">${{
      A: '冷调深底 · 电光蓝强调 · 硬朗几何 · 14px 正文',
      B: '浅青灰底 · 青瓷绿强调 · 大圆角柔和 · 纸感留白',
      C: '紫黑底 · 品红强调 · 玻璃浮层 · 辉光阴影',
    }[c.key]}</p>
    <div class="lab">会话</div><div class="row2"><img src="file://${c.chat}"></div>
    <div class="lab">工作</div><div class="row2"><img src="file://${c.work}"></div>
    <div class="lab">设置</div><div class="row2"><img src="file://${c.settings}"></div>
  </div>`).join('')}
  </div>`;
  const sheetFile = path.join(OUT, 'compare.html');
  fs.writeFileSync(sheetFile, sheet, 'utf8');
  await page.setViewport({ width: 1500, height: 1000, deviceScaleFactor: 1 });
  await page.goto('file://' + sheetFile, { waitUntil: 'networkidle0' });
  await new Promise(r => setTimeout(r, 400));
  await page.screenshot({ path: path.join(OUT, 'compare.png'), fullPage: true });
  console.log('shot compare');
  await browser.close();
})();
