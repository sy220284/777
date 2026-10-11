// Mobile PptxGenJS execution; dependencies live in the user's project.
const fs = require('node:fs');
const path = require('node:path');
async function main() {
  const [input, output, modulePath] = process.argv.slice(2);
  if (!input || !output || !modulePath) throw Error('用法：node mobile_pptx.cjs content.json output.pptx /绝对路径/node_modules/pptxgenjs');
  const PptxGenJS = require(path.resolve(modulePath));
  const data = JSON.parse(fs.readFileSync(input, 'utf8'));
  if (!Array.isArray(data.slides) || !data.slides.length) throw Error('slides 不能为空');
  const pptx = new PptxGenJS();
  pptx.layout = 'LAYOUT_WIDE';
  pptx.title = data.title || '演示文稿';
  pptx.lang = 'zh-CN';
  pptx.theme = {headFontFace:'Noto Sans CJK SC',bodyFontFace:'Noto Sans CJK SC',lang:'zh-CN'};
  const escape = s => String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const preview = [];
  for (const [index, item] of data.slides.entries()) {
    if (!Array.isArray(item.body)) throw Error('第 '+(index+1)+' 页 body 应为文字数组');
    const slide = pptx.addSlide();
    slide.background = {color:'122033'};
    slide.addText(String(item.title || ''), {x:0.65,y:0.55,w:12,h:1.05,fontSize:30,bold:true,color:'F4D79B',breakLine:false});
    slide.addText(item.body.map(line => ({text:String(line),options:{breakLine:true,bullet:true}})),
      {x:0.8,y:1.9,w:11.7,h:4.7,fontSize:22,color:'FFFFFF',paraSpaceAfterPt:16,margin:0,fit:'shrink'});
    slide.addText(String(index+1),{x:12.2,y:7,w:0.5,h:0.25,fontSize:10,color:'FFFFFF'});
    preview.push('<section><h1>'+escape(item.title||'')+'</h1><ul>'+item.body.map(t=>'<li>'+escape(t)+'</li>').join('')+'</ul><small>'+(index+1)+'</small></section>');
  }
  fs.mkdirSync(path.dirname(path.resolve(output)),{recursive:true});
  await pptx.writeFile({fileName:output});
  const html = '<!doctype html><html lang="zh"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>'+escape(data.title||'演示')+'</title><style>body{margin:0;background:#122033;color:white;font:20px/1.6 sans-serif}section{padding:6vw;min-height:90vh;box-sizing:border-box;border-bottom:2px solid #f4d79b;overflow-wrap:anywhere}h1{color:#f4d79b}li{margin:1em 0}</style><body>'+preview.join('')+'</body></html>';
  fs.writeFileSync(output.replace(/\.pptx$/i,'')+'.html',html,'utf8');
  process.stdout.write(JSON.stringify({file:output,slides:data.slides.length})+'\n');
}
main().catch(error=>{process.stderr.write(error.message+'\n');process.exitCode=1;});
