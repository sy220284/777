#!/usr/bin/env bash

# Gradle 离线参数必须覆盖 Exec 子进程，不能只限制 Maven 依赖解析。
runtime_download() {
  if [ "${DSH_RUNTIME_OFFLINE:-false}" = true ]; then
    echo "离线构建所需 Runtime 缓存缺失或校验失败；请重新下载 GitHub Actions Runtime Artifact。" >&2
    return 1
  fi
  command curl "$@"
}
