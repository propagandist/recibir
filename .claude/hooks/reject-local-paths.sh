#!/bin/sh
# PreToolUse(Write|Edit): .claude/settings.json へ手元固有の絶対パスが書かれたら実行前に拒否する。
#
# ★ なぜ要るか — 実際に踏んだ（#77）。
#   org のリポジトリで、permissions の allow と additionalDirectories に作業者の手元の絶対パスが
#   commit されていた。**別の作業者の環境には存在しない**うえ、パスの一部が作業ツリーの名前なので
#   **その作業が終われば消える**。設定ファイルの中で「状態を書いた」形になっている。
#   **規約では止まらなかった**——規約（docs/work-conventions.md）はこの時点で無かったが、
#   入っている形が **1 回限りのコマンドがそのまま規則になったもの**（`Bash(explorer.exe "d:\…")` /
#   `Bash(cmd /c "mklink /D …")`）なので、**人が読んで書いたとは考えにくい**。だから構造で塞ぐ
#   （reject-powershell-syntax.sh と同じ判断）。**どの経路で入ったかは確かめていない。**
#
# ★ 未実走 — **ツール呼び出しを通らない書き込み**（許可の応答が設定へ落ちる経路）を、この関門が
#   捕まえるかは確かめていない。捕まえないなら、そこは規約の側だけが受け持つ。
#   **確かめたら、ここを書き換える。**
#   行き先の候補は **ConfigChange hook**——設定ファイルが変わったときに発火し、
#   `source`（どの設定か）と `file_path` を受け取る（2026-08-27 に発火まで実測）。
#   **ただし入力に変更内容は入らず、止めてもファイルは既に書き換わっている**ので、
#   この関門の代わりにはならない。**どちらが要るかは、経路を測ってから決める。**
#
# ★ 対象は .claude/settings.json だけ。**起動条件はスクリプトが持たない**——
#   settings.json 側の `if` で Write / Edit のそれぞれに絞ってある（`if` が相対パスの表記で
#   絶対パスの file_path に照合できることは実測した）。
#   **.claude/settings.local.json は対象にしない。** あれは手元のもので、commit しない側。
#
# ★★ JSON 全体を grep しない。**hook の入力には transcript_path・cwd・file_path が必ず入り、
#   どれも絶対パス**なので、全体を見ると必ず当たる——「常に拒否」になって使いものにならない。
#   tool_input 以降を取り、その中の file_path を落としてから見る。
#   （reject-powershell-syntax.sh が全体を grep できるのは、探すのが `@'` + 改行という
#   入力側には現れない並びだから。**同じ形を持ち込まない。**）
#
# ★ jq に依存しない — Git Bash に jq は無い。sed と grep だけで書く。
#
# ★ 何を「手元固有」と見るか — 3 つだけ。**推測で増やさない。**
#   1. ドライブレター（`D:\` `d:/`）
#   2. 引用符か開き括弧の直後のドライブ表記（`"Read(//d/…` `"Bash(/d/…`）
#   3. `/home/` と `/Users/`
#   相対パス（`.claude/hooks/…`）と `${CLAUDE_PROJECT_DIR}` には当たらない。
#
# ★★ 1 の前に「英数字が来ないこと」を要求している。要求しないと **`https://` の `s:/` に当たる**
#   ——`$schema` の URL を書いた瞬間に、正当な設定が拒否される（実際に踏みかけた）。
#   **ドライブレターは 1 文字**なので、直前が英数字なら別のものだと分かる。
#
# ★ 誤検知したときの代償は「その 1 回の書き込みが止まる」だけ。人が手で直せば通る。
#
# 終了コード 2 = PreToolUse をブロックし、stderr をモデルへ返す。

input=$(cat)

body=$(printf '%s' "$input" | sed 's/.*"tool_input"://; s/"file_path":"[^"]*"//')

if printf '%s' "$body" | grep -qE '(^|[^A-Za-z0-9])[A-Za-z]:[\\/]|[("]/{1,2}[A-Za-z0-9]/|/(home|Users)/'; then
  cat >&2 <<'MSG'
.claude/settings.json へ手元固有の絶対パスを書こうとしている。

このファイルは commit され、別の作業環境へそのまま配られる。書いた側の手元にしか無いパスは
そこに存在しないし、作業ツリーの名前を含むパスは、その作業が終われば消える。

- 手元だけで要るものは .claude/settings.local.json へ（あちらは commit しない）
- リポジトリ内を指すなら相対パスか ${CLAUDE_PROJECT_DIR} を使う

判断規約は docs/work-conventions.md の「エージェントの設定をどこへ書くか」。
MSG
  exit 2
fi

exit 0
