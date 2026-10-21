#!/usr/bin/env node
/**
 * version-release-client 启动器：
 * 打开随包发布的 index.html（版本发布管理客户端）。
 * 用法：npx @200111226011qw-debug/version-release-client
 */
'use strict';

const { exec } = require('child_process');
const fs = require('fs');
const path = require('path');

const htmlPath = path.resolve(__dirname, '..', 'index.html');

if (!fs.existsSync(htmlPath)) {
  console.error('未找到客户端文件 index.html，安装可能不完整。');
  process.exit(1);
}

const commands = {
  win32: 'start "" "' + htmlPath + '"',
  darwin: 'open "' + htmlPath + '"',
  linux: 'xdg-open "' + htmlPath + '"'
};

const command = commands[process.platform] || commands.linux;

exec(command, function (error) {
  if (error) {
    console.error('无法自动打开浏览器，请手动打开以下文件：');
    console.error(htmlPath);
    process.exit(1);
  }
  console.log('已在浏览器中打开「版本发布管理客户端」');
  console.log(htmlPath);
});
