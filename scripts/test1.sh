#chmod +x hellworl
#./hellworl
#python3 ploop1.py
#chmod +x gohello
#./gohello
go build gohello.go
./gohello


OUTPUT_FILE="result.txt"

echo "Device Information:" > "$OUTPUT_FILE"
echo "-------------------" >> "$OUTPUT_FILE"

# Get system information
echo "System: $(uname -s)" >> "$OUTPUT_FILE"
echo "Node Name: $(uname -n)" >> "$OUTPUT_FILE"
echo "Release: $(uname -r)" >> "$OUTPUT_FILE"
echo "Version: $(uname -v)" >> "$OUTPUT_FILE"
echo "Machine: $(uname -m)" >> "$OUTPUT_FILE"

# Get processor information
echo "Processor: $(cat /proc/cpuinfo | grep 'model name' | head -n 1 | awk -F ':' '{print $2}' | sed 's/^[[:space:]]*//')" >> "$OUTPUT_FILE"

# Get memory information
echo "Memory: $(free -h | awk '/^Mem:/ {print $2}')" >> "$OUTPUT_FILE"

# Get disk space information
echo "Disk Space: $(df -h / | awk '/^\/dev/ {print $4}')" >> "$OUTPUT_FILE"

# Get network information
echo "ip: $(hostname -I | awk '{print $1}')" >> "$OUTPUT_FILE"
echo "Hostname: $(hostname)" >> "$OUTPUT_FILE"
echo "macid: 90:49:fa:09:bd:b4" >> "$OUTPUT_FILE"
echo "macid: $(ip -br link | awk '{print $3,$(NF-2)}' | grep "UP" | awk '{print $2}')" >> "$OUTPUT_FILE"
# Get uptime
echo "Uptime: $(uptime -p)" >> "$OUTPUT_FILE"

echo "Device information saved to $OUTPUT_FILE"

echo "done" >> $OUTPUT_FILE
