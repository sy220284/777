#!/usr/bin/env python3
"""Render and exercise the V7 HTML design preview. No Android build or device claim.

Requires an existing Playwright installation and Chromium; installs nothing.
Run from any directory: python3 docs/design/v7/verify-preview.py
"""
from functools import partial
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from threading import Thread
import shutil
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[3]
OUT = Path(__file__).resolve().parent / 'previews'
ROUTES = ['chat', 'work', 'personas', 'diary', 'files', 'tasks', 'tools', 'settings']


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *_args):
        pass


def run():
    chromium = shutil.which('chromium') or shutil.which('google-chrome')
    if not chromium:
        raise SystemExit('需要已安装的 Chromium；本脚本不会下载或安装浏览器。')
    OUT.mkdir(exist_ok=True)
    server = ThreadingHTTPServer(('127.0.0.1', 0), partial(QuietHandler, directory=str(ROOT)))
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    url = f'http://127.0.0.1:{server.server_port}/docs/design/v7/mobile/index.html'
    errors, failed_responses = [], []
    try:
        with sync_playwright() as p:
            browser = p.chromium.launch(executable_path=chromium, args=['--no-sandbox'])
            page = browser.new_page(viewport={'width': 1200, 'height': 1050})
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.on('response', lambda r: failed_responses.append(r.url) if r.status >= 400 else None)
            for route in ROUTES:
                page.goto(url + '#' + route)
                page.wait_for_timeout(250)  # Let the specified 220ms page transition settle for capture.
                assert page.locator('#app').evaluate('(e)=>e.scrollWidth<=e.clientWidth'), route
                assert page.locator('img').evaluate_all('(a)=>a.every(i=>i.complete&&i.naturalWidth>0)'), route
                assert page.locator('#app button').evaluate_all('''(a)=>a.every(e=>{
                    const r=e.getBoundingClientRect();return r.width>=48&&r.height>=48;
                })'''), route + ' touch targets'
                page.locator('#device').screenshot(path=str(OUT / (route + '-light.png')))
                for k in ['dark', 'large', 'short', 'error', 'reduced']:
                    page.check('#' + k)
                assert page.locator('#app').evaluate('(e)=>e.scrollWidth<=e.clientWidth'), route + ' pressure'
                page.locator('#device').screenshot(path=str(OUT / (route + '-pressure.png')))
                for k in ['dark', 'large', 'short', 'error', 'reduced']:
                    page.uncheck('#' + k)
            page.goto(url + '#work')
            page.get_by_role('button', name='查看待确认事项').click()
            assert page.get_by_role('dialog', name='发布前，再确认两件事').is_visible()
            page.locator('#device').screenshot(path=str(OUT / 'approval-sheet.png'))
            page.keyboard.press('Escape')
            assert page.locator('#overlay').evaluate('(e)=>e.childElementCount===0')
            page.get_by_role('button', name='打开导航').click()
            page.locator('#device').screenshot(path=str(OUT / 'navigation-drawer.png'))
            page.get_by_role('button', name='工作区文件', exact=True).click()
            assert page.url.endswith('#files')
            page.get_by_role('textbox', name='搜索文件').fill('没有这个文件')
            assert page.locator('#file-list .file-row:visible').count() == 0
            page.goto(url + '#chat')
            page.get_by_role('textbox', name='发送消息').fill('长文本与中文消息交互检查')
            page.get_by_role('button', name='发送', exact=True).click()
            assert page.get_by_role('button', name='停止', exact=True).is_visible()
            page.get_by_role('button', name='停止', exact=True).click()
            assert page.get_by_role('button', name='发送', exact=True).is_visible()
            for width in [320, 390, 430]:
                page.set_viewport_size({'width': width, 'height': 820})
                page.goto(url + '#personas')
                page.check('#large')
                assert page.locator('#app').evaluate('(e)=>e.scrollWidth<=e.clientWidth'), str(width)
            assert not errors, errors
            assert not failed_responses, failed_responses
            browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    print('PASS: 8 scenes, 18 captures, 48px targets, state variants, 320/390/430px, images, drawer/sheet/search/send/stop.')
    print('HTML design preview only; Android runtime evidence is separate.')


if __name__ == '__main__':
    run()
