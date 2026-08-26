#!/bin/sh
# PreToolUse(Bash): PowerShell の here-string が Bash ツールへ渡されたら実行前に拒否する。
#
# ★ なぜ要るか — 2026-08-22 に実際に踏んだ。
#   この org の作業環境には Bash ツールと PowerShell ツールが並んでいる。PowerShell の
#   here-string `@'...'@` を Bash（POSIX sh）へ渡すと `@` がリテラルとして残り、
#   `git commit -m @'...'@` はコミットメッセージの先頭に `@` を持ったまま通る。
#   **規約文では防げない**——同じ禁止事項はエージェントのツール説明に元から書かれていて、
#   それでも破られた。取り違えは知識の欠落ではないので、関門の側で塞ぐ。
#
# ★ jq に依存しない — Git Bash に jq は無い（2026-08-22 実測）。stdin の JSON を
#   そのまま grep -F にかける。here-string は **必ず `@'` の直後で改行する**ので、
#   JSON 上は `@'` + `\n`（エスケープされた 2 バイト）の並びになる。
#   `grep 'foo@'` のような正当な用法は直後に改行が来ないため当たらない。
#
# ★ 対象は Bash ツールだけ。逆方向（PowerShell ツールへ heredoc）は実際には踏んでいない
#   ので足さない。推測で判定を増やすと、誤検知の代償だけが残る。
#
# 終了コード 2 = PreToolUse をブロックし、stderr をモデルへ返す。

input=$(cat)

if printf '%s' "$input" | grep -qF -e "@'\n" -e "\n'@" -e '@\"\n' -e '\n\"@'; then
  cat >&2 <<'MSG'
PowerShell の here-string（@'...'@ / @"..."@）が Bash ツールへ渡されている。
Bash は POSIX sh なので @ がリテラルとして残り、コミットメッセージなどに紛れ込む。

複数行の文字列は heredoc を使うこと:

  git commit -F - <<'EOF'
  件名

  本文
  EOF

PowerShell の構文が要るなら PowerShell ツールを使う。
MSG
  exit 2
fi

exit 0
