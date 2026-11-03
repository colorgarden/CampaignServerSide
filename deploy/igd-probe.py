"""Read-only UPnP IGD probe: asks the router for its external IP and any mapping on port 6567.

Uses only the standard library. Does not create, change or delete a mapping.
"""
import re
import socket
import urllib.request
import xml.etree.ElementTree as ET

PORT = 6567
MS = (
    "M-SEARCH * HTTP/1.1\r\n"
    "HOST: 239.255.255.250:1900\r\n"
    'MAN: "ssdp:discover"\r\n'
    "MX: 2\r\n"
    "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
)

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
sock.settimeout(3)
sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
sock.sendto(MS.encode(), ("239.255.255.250", 1900))

locations = []
try:
    while True:
        data, _addr = sock.recvfrom(65507)
        match = re.search(r"LOCATION:\s*(\S+)", data.decode(errors="ignore"), re.I)
        if match and match.group(1) not in locations:
            locations.append(match.group(1))
except socket.timeout:
    pass

print("igd_locations:", locations)
if not locations:
    print("no IGD answered - UPnP is probably disabled on the router")
    raise SystemExit(0)


def find_wan_service(location):
    body = urllib.request.urlopen(location, timeout=5).read()
    root = ET.fromstring(body)
    for service in root.iter():
        if service.tag.endswith("service"):
            fields = {child.tag.split("}")[-1]: (child.text or "") for child in service}
            stype = fields.get("serviceType", "")
            if "WANIPConnection" in stype or "WANPPPConnection" in stype:
                return location, stype, fields.get("controlURL", "")
    return None


def soap(location, stype, control, action, args=""):
    base = re.match(r"(https?://[^/]+)", location).group(1)
    url = base + control
    envelope = (
        '<?xml version="1.0"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        "<s:Body><u:%s xmlns:u=\"%s\">%s</u:%s></s:Body></s:Envelope>"
        % (action, stype, args, action)
    )
    request = urllib.request.Request(
        url,
        data=envelope.encode(),
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPAction": '"%s#%s"' % (stype, action),
        },
    )
    return urllib.request.urlopen(request, timeout=5).read().decode(errors="ignore")


for location in locations:
    found = find_wan_service(location)
    if not found:
        continue
    location, stype, control = found
    print("wan_service:", stype)
    try:
        print("external_ip:", re.search(r"<NewExternalIPAddress>(.*?)<", soap(location, stype, control, "GetExternalIPAddress")).group(1))
    except Exception as exc:
        print("external_ip: error", exc)

    args = (
        "<NewRemoteHost></NewRemoteHost>"
        "<NewExternalPort>%d</NewExternalPort><NewProtocol>TCP</NewProtocol>" % PORT
    )
    try:
        print("tcp_mapping:", soap(location, stype, control, "GetSpecificPortMappingEntry", args).strip())
    except Exception as exc:
        print("tcp_mapping: none or error ->", exc)
    break
