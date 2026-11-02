#!/usr/bin/env bash
# Starts the Mindustry campaign server in the foreground.
# Normally started through ./server.sh (start|stop|status|console).
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

mkdir -p logs
FIFO="$DIR/console.in"
if [ ! -p "$FIFO" ]; then
  rm -f "$FIFO"
  mkfifo "$FIFO"
fi

# the shell pid becomes the java pid after exec, so server.sh can track it
echo $$ > "$DIR/server.pid"

# keep a read-write handle so the server's stdin never reaches EOF
exec 3<>"$FIFO"

JAVA="${JAVA:-java}"
# UTF-8 everywhere: the JVM default charset follows the locale, which is often POSIX on a server,
# and non-ASCII (e.g. Chinese) messages would be written as '?' otherwise.
JAVA_OPTS="${JAVA_OPTS:--Xms512M -Xmx2G -XX:+UseG1GC -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8}"

echo "[start.sh] $(date -Is) pid $$ starting: $JAVA $JAVA_OPTS -jar server-release.jar" >> logs/start.log
exec "$JAVA" $JAVA_OPTS -jar server-release.jar "$@" <&3
