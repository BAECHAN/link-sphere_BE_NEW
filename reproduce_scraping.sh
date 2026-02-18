#!/bin/bash
URL="https://stackoverflow.com/questions/66614875/how-can-i-enable-tailwindcss-intellisense-outside-of-classname"
echo "Trying to fetch $URL with curl (default user agent)..."
curl -I -v "$URL"
echo ""
echo "Trying to fetch $URL with curl (simulating browser)..."
curl -I -v -A "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" "$URL"
