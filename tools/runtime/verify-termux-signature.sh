#!/usr/bin/env bash

# 只验证签名；不导入密钥、不启动 gpg-agent、不依赖用户默认密钥环。
verify_termux_signature() {
  local inrelease="$1" keyring="$2" expected_fingerprint="$3" homedir="$4"
  local status
  status="$(gpgv --homedir "$homedir" --status-fd 1 --keyring "$keyring" "$inrelease")" || return 1
  # VALIDSIG 的签名子钥及主钥均由已验证签名关联，禁止仅凭密钥环包含目标钥放行。
  if ! awk -v expected="$expected_fingerprint" '
    $1 == "[GNUPG:]" && $2 == "VALIDSIG" && ($3 == expected || $NF == expected) { valid = 1 }
    END { exit !valid }
  ' <<< "$status"; then
    echo "Termux 仓库签名者公钥指纹不匹配" >&2
    return 1
  fi
}
