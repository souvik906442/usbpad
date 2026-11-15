name: Build PC server
on:
  workflow_dispatch:
  push:
    branches: [main]
    paths: ["server/**", ".github/workflows/server.yml"]
jobs:
  exe:
    runs-on: windows-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with:
          python-version: "3.12"
      - run: pip install pyinstaller pystray pillow
      - run: pyinstaller --onefile --noconsole --name UsbPadServer --add-data "server/server.py;." server/tray.py
      - uses: actions/upload-artifact@v4
        with:
          name: UsbPadServer-exe
          path: dist/UsbPadServer.exe
