// Fixtures for NetRulesTest.cpp
#include <QByteArray>

QByteArray testData(int i)
{
    static const char *data[] = {
        R"({"AccessToken":"abc","ServerId":"srv","User":{"Id":"u1","Name":"test"}})",
        R"({"Items":[
            {"Id":"ep1","Name":"Pilot","Type":"Episode","IndexNumber":2,"ParentIndexNumber":1,"SeriesName":"Show",
             "RunTimeTicks":36000000000,"UserData":{"PlaybackPositionTicks":6000000000,"Played":false},
             "ImageTags":{"Primary":"tag1"},
             "MediaSources":[{"Id":"ms1","MediaStreams":[{"Type":"Subtitle","IsExternal":true,"IsTextSubtitleStream":true,"Index":3}]}]},
            {"Id":"lib","Name":"Movies","Type":"CollectionFolder","IsFolder":true,"CollectionType":"movies"},
            {"Id":"m1","Name":"Film","Type":"Movie","ProductionYear":2019},
            {"Name":"no id"}]})",
        R"([{"Id":"x","Name":"Bare","Type":"Video"}])",
        R"(<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
 <d:response><d:href>/remote.php/dav/files/test/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/test/Ep%2010.mkv</d:href><d:propstat><d:prop><d:getcontentlength>2048</d:getcontentlength><d:getcontenttype>video/x-matroska</d:getcontenttype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
   <d:propstat><d:prop><d:displayname/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/test/Ep%202.mkv</d:href><d:propstat><d:prop><oc:size>10</oc:size></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/test/Shows/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
 <d:response><d:href>/remote.php/dav/files/test/.hidden</d:href><d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
</d:multistatus>)",
        R"(<?xml version="1.0"?>
<root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://10.0.0.5:8200/</URLBase>
<device><friendlyName>Living room TV</friendlyName><UDN>uuid:tv</UDN><manufacturer>Acme</manufacturer>
<iconList><icon><url>/icon.png</url></icon></iconList>
<serviceList>
 <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/rc</controlURL></service>
 <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL></service>
</serviceList>
<deviceList><device><friendlyName>Inner</friendlyName><serviceList><service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>cd/control</controlURL></service></serviceList></device></deviceList>
</device></root>)",
        R"(<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
<container id="1" childCount="4"><dc:title>Films</dc:title><upnp:class>object.container</upnp:class></container>
<item id="2"><dc:title>Clip &amp; more</dc:title><upnp:class>object.item.videoItem</upnp:class>
 <res protocolInfo="http-get:*:image/jpeg:*">http://h/thumb.jpg</res>
 <res protocolInfo="http-get:*:video/mp4:*" duration="0:01:02.500" size="999">http://h/clip.mp4</res>
 <res protocolInfo="http-get:*:text/srt:*">http://h/clip.srt</res></item>
<item id="3"><dc:title>Photo</dc:title><upnp:class>object.item.imageItem</upnp:class><res protocolInfo="http-get:*:image/jpeg:*">http://h/p.jpg</res></item>
</DIDL-Lite>)"
    };
    return QByteArray(data[i]);
}
