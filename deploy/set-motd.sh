#!/usr/bin/env bash
# Sets the vanilla MOTD. Kept in a file so the colour markup never reaches the shell parser.
set -u

DIR=/home/colorgarden/campaign-server

MOTD='[#ffd166]>> [white]Campaign Server Side [#ffd166]v0.0.2-alpha[] [gray]| [white]投票 [#8ecae6]/cv menu[] [gray]| [white]管理 [#8ecae6]/ca menu[] [gray]| [white]帮助 [#8ecae6]/cv help[] [gray]| [#8ecae6]colorgarden.top'

runuser -u colorgarden -- "$DIR/server.sh" console "config motd $MOTD"
sleep 2
runuser -u colorgarden -- "$DIR/server.sh" console 'config motd'
sleep 2
sed 's/\x1b\[[0-9;]*m//g' "$DIR/logs/server.log" | tail -4

# remove the junk the previous, broken invocation created
cd "$DIR" || exit 0
for f in Campaign Server Side 'v0.0.2-alpha' '[gray]' 'colorgarden.top' '&'; do
  [ -f "$f" ] && rm -f "$f" && echo "removed stray file: $f"
done
