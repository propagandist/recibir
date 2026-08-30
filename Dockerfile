# jar を包むだけの単段である。ビルドはここで回さない（#56 の判断 2）。
#
# jOOQ の codegen はスキーマの入った PostgreSQL を要求する（CLAUDE.md「ビルドと実行」）。
# ビルド段階を Docker に持ち込むと、その DB をどう用意するかという問題がそのまま移る。
# #48 は「Railway のビルド段階から DB へ到達できるか」を、公式ドキュメントの
# 「どちらにも記載が無い」と記録している——「無い」ではなく「書かれていない」。
# 同じ未確認を Docker のビルド段階へ持ち込まないために、jar は
# .github/workflows/image.yml が service container 付きで作り、ここは包むだけにする。
#
# ★ base image は digest でピンしてある（同 判断 9）。末尾のタグのコメントを消さないこと
#   ——Dependabot が digest と一緒に書き換える対象で、消すとどの版なのか読めなくなる
#   （.github/dependabot.yml の docker entry。action を SHA でピンするのと同じ形）。
#
# ★ JDK ではなく JRE を採る。実行に javac は要らず、イメージに入れるものが減る。
#   版は build.gradle.kts の toolchain と .github/workflows/ の setup-java に揃えてある。
FROM eclipse-temurin:25-jre@sha256:f9e65324a37f28209ce7dd0e5149a7aa954520ed936fb87813cf6ded2400a112 # 25-jre

# root で動かさない。ベースイメージが非 root のユーザーを持たないので、ここで作る。
# 受けるのは外部から届く POST であり（docs/SPEC.md §4.2）、プロセスが権限を持つ理由が無い。
RUN useradd --system --create-home --shell /usr/sbin/nologin recibir
USER recibir
WORKDIR /home/recibir

# 包むのは 1 つの jar だけである。ここで名前を決め打たないのは、version を持つのが
# build.gradle.kts の側だからで、固定名にするのは image.yml の仕事になる。
COPY app.jar app.jar

# アプリが listen する既定のポート（Spring Boot の既定）。
# 実運用でどう当てるかは本体の関知するところではない——接続先と同じく
# recibir-ops が環境変数で渡す（#1）。
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
