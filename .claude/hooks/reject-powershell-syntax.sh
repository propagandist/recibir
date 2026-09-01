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
#   そのまま grep -F にかける。JSON 上では改行が「バックスラッシュ + n」の 2 バイト、
#   二重引用符が「バックスラッシュ + 二重引用符」の 2 バイトになる。
#
# ★★ **見るのは「閉じ」だけ。開きは見ない**（propagandist/cartera#380）。
#   here-string の**閉じ記号は行頭（列 0）に来ることが PowerShell の要件**なので、JSON 上では
#   必ず「改行 + 閉じ記号」の並びで現れる。**閉じない here-string は無い**ので取りこぼさない。
#   **開き側を見ると、正当な bash を巻き込む**——**行末が引数展開で終わる行**
#   （`main "$@"` / `exec "$@"` はシェルのラッパの定型）と、**行末が単引用符 + @ で終わる行**
#   （`grep 'foo@'` など）が、開きの並びと区別できない。**前者は実際に拒否された。**
#   `@` の直前が `$` でないことを足す案もあったが、**後者が残る**ので採らなかった。
#
# ★★ **判定に使う並びを、リテラルで書かない。** そのまま書くと**このファイル自身が
#   自分の判定に当たり、Bash ツールから編集も grep もできなくなる**——
#   `.claude/rules/agent-settings.md` §3 の「禁止したい文字列を判定に使う hook は、その文字列を
#   含むテストコマンド自身を止める／規約を grep する作業も止まる」が、**この関門自身で起きていた**。
#   8 進エスケープから組み立てれば、ファイルの本文に並びが現れない。
#
# ★ 対象は Bash ツールだけ。逆方向（PowerShell ツールへ heredoc）は実際には踏んでいない
#   ので足さない。推測で判定を増やすと、誤検知の代償だけが残る。
#
# ★ **通るべきものが通ることまで見る検査**は `scripts/test-reject-powershell-syntax.sh`
#   （`.claude/rules/agent-settings.md` §3「足したら pipe-test する」）。
#   **配る先には無い**——このファイルを直すのは正本の側だけなので、検査も正本が持つ。
#
# 終了コード 2 = PreToolUse をブロックし、stderr をモデルへ返す。

input=$(cat)

# 8 進: 47 = 単引用符 ／ 42 = 二重引用符 ／ 134 = バックスラッシュ
sq=$(printf '\47')
dq=$(printf '\42')
bs=$(printf '\134')

# JSON 上の「改行 + 閉じ記号」。前者が単引用符の here-string、後者が二重引用符の here-string。
close_sq="${bs}n${sq}@"
close_dq="${bs}n${bs}${dq}@"

if printf '%s' "$input" | grep -qF -e "$close_sq" -e "$close_dq"; then
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
