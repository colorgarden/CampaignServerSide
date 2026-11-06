#!/usr/bin/env bash
cd /home/colorgarden/campaign-server || exit 1

./server.sh tmux < /dev/null > /tmp/t4.txt 2>&1 &
sleep 6

echo "=== panes before ==="
tmux list-panes -t campaign -F '#{pane_index}:#{pane_current_command}'

echo "=== press Ctrl+C in input pane ==="
tmux send-keys -t campaign:console.1 C-c
sleep 1
tmux list-panes -t campaign -F '#{pane_index}:#{pane_current_command}'
tmux capture-pane -p -t campaign:console.1 | tail -3

echo "=== type after Ctrl+C ==="
tmux send-keys -t campaign:console.1 'campaign-status' Enter
sleep 2
tmux capture-pane -p -t campaign:console.1 | tail -4
