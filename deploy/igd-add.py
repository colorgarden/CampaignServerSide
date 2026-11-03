"""Adds one UPnP port mapping. Usage: add-mapping.py <tcp|udp> <ext> <internal-ip> <internal-port>"""

import re
import socket
import sys
import urllib.request
import xml.etree.ElementTree as ET

if len(sys.argv) != 5:
    raise SystemExit(__doc__)

protocol, external, internal_ip, internal_port = sys.argv[1].upper(), sys.argv[2], sys.argv[3], sys.argv[4]

MS = (
    "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n"
    'MAN: "ssdp:discover"\r\nMX: 2\r\n'
    "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"
)

sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
sock.settimeout(3)
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

if not locations:
    raise SystemExit("no IGD answered")

location = locations[0]
root = ET.fromstring(urllib.request.urlopen(location, timeout=5).read())
control = stype = None
for service in root.iter():
    if service.tag.endswith("service"):
        fields = {child.tag.split("}")[-1]: (child.text or "") for child in service}
        if "WANIPConnection" in fields.get("serviceType", "") or "WANPPPConnection" in fields.get("serviceType", ""):
            stype = fields["serviceType"]
            control = fields["controlURL"]
            break

if not control:
    raise SystemExit("no WAN service")

base = re.match(r"(https?://[^/]+)", location).group(1)


def soap(action, args=""):
    envelope = (
        '<?xml version="1.0"?>'
        '<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
        's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">'
        "<s:Body><u:%s xmlns:u=\"%s\">%s</u:%s></s:Body></s:Envelope>"
        % (action, stype, args, action)
    )
    request = urllib.request.Request(
        base + control,
        data=envelope.encode(),
        headers={
            "Content-Type": 'text/xml; charset="utf-8"',
            "SOAPAction": '"%s#%s"' % (stype, action),
        },
    )
    return urllib.request.urlopen(request, timeout=5).read().decode(errors="ignore")


args = (
    "<NewRemoteHost></NewRemoteHost>"
    "<NewExternalPort>%s</NewExternalPort>"
    "<NewProtocol>%s</NewProtocol>"
    "<NewInternalPort>%s</NewInternalPort>"
    "<NewInternalClient>%s</NewInternalClient>"
    "<NewEnabled>1</NewEnabled>"
    "<NewPortMappingDescription>mindustry</NewPortMappingDescription>"
    "<NewLeaseDuration>0</NewLeaseDuration>"
    % (external, protocol, internal_port, internal_ip)
)

try:
    soap("AddPortMapping", args)
    print("added %s %s -> %s:%s" % (protocol, external, internal_ip, internal_port))
except Exception as exc:
    print("add failed (%s)" % exc)
    print("likely already mapped or the router refuses UPnP writes")

try:
    check = soap(
        "GetSpecificPortMappingEntry",
        "<NewRemoteHost></NewRemoteHost>"
        "<NewExternalPort>%s</NewExternalPort>"
        "<NewProtocol>%s</NewProtocol>" % (external, protocol),
    )
    fields = {tag: (val or "") for tag, val in re.findall(r"<New(\w+)>(.*?)</New\1>", check)}
    print("verified:", fields)
except Exception as exc:
    print("verify failed (%s)" % exc)
