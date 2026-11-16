// 渲染三版变体的全部页面，并合成总册对比图。
const puppeteer = require('puppeteer');
const path = require('path');
const fs = require('fs');

const DIR = process.argv[2] || '/root/.dsh/bot/workspace/777/docs/design/v8/mobile';
const OUT = process.argv[3] || path.join(DIR, 'shots');
const EXEC = '/root/.cache/puppeteer/chrome-headless-shell/linux_arm-157.0.8103.78/chrome-headless-shell-linux-arm64/chrome-headless-shell';

const VARIANTS = [
  { key: 'A', name: '夜航 · 靛蓝', theme: 'dark' },
  { key: 'B', name: '青瓷 · 苔绿', theme: 'light' },
  { key: 'C', name: '绯夜 · 品红', theme: 'dark' },
];
const PAGES = [
  { key: 'chat', label: '会话', q: 'route=HOME' },
  { key: 'work', label: '工作', q: 'route=HOME&mode=WORK' },
  { key: 'persona', label: '人物', q: 'route=PERSONA_GALLERY' },
  { key: 'diary', label: '日记', q: 'route=DIARY' },
  { key: 'workspace', label: '工作区', q: 'route=WORKSPACE' },
  { key: 'tasks', label: '任务', q: 'route=TASKS' },
  { key: 'tools', label: '能力', q: 'route=TOOLS' },
  { key: 'settings', label: '设置', q: 'route=SETTINGS' },
  { key: 'drawer', label: '侧栏', q: 'route=HOME&drawer=1' },
];

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: EXEC, headless: true,
    args: ['--no-sandbox', '--disable-dev-shm-usage', '--allow-file-access-from-files'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 393, height: 852, deviceScaleFactor: 2 });

  const perVariant = {};
  for (const v of VARIANTS) {
    perVariant[v.key] = [];
    for (const p of PAGES) {
      await page.goto('file://' + path.join(DIR, `app-${v.key}.html`) + '?' + p.q + '&theme=' + v.theme, { waitUntil: 'networkidle0' });
      await new Promise(r => setTimeout(r, 260));
      const app = await page.$('#app');
      const file = path.join(OUT, `${v.key}-${p.key}.png`);
      await app.screenshot({ path: file });
      perVariant[v.key].push({ file, label: p.label });
    }
    console.log('rendered variant', v.key);
  }

  // 每版一张总册
  for (const v of VARIANTS) {
    const html = `<!doctype html><meta charset="utf-8"><style>
      body{margin:0;background:#12100F;font-family:-apple-system,"Noto Sans SC",sans-serif;padding:26px}
      h1{color:#F2EBE3;font-size:26px;margin:0 0 4px}
      p{color:#9C9189;font-size:13px;margin:0 0 18px}
      .grid{display:grid;grid-template-columns:repeat(5,1fr);gap:14px}
      figure{margin:0}
      figcaption{color:#8A7F77;font-size:11px;margin:0 0 5px;letter-spacing:.06em}
      img{width:100%;border-radius:10px;border:1px solid #322C2A;display:block}
    </style><h1>${v.name}</h1><p>${v.key} 版 · 全部页面渲染</p><div class="grid">
    ${perVariant[v.key].map(s => `<figure><figcaption>${s.label}</figcaption><img src="file://${s.file}"></figure>`).join('')}
    </div>`;
    const f = path.join(OUT, `sheet-${v.key}.html`);
    fs.writeFileSync(f, html, 'utf8');
    await page.setViewport({ width: 1700, height: 1000, deviceScaleFactor: 1 });
    await page.goto('file://' + f, { waitUntil: 'networkidle0' });
    await new Promise(r => setTimeout(r, 400));
    await page.screenshot({ path: path.join(OUT, `sheet-${v.key}.png`), fullPage: true });
    console.log('sheet', v.key);
  }
  await browser.close();
})();
