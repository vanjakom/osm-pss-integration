
issues:

CLAUDE-1
Modify "compare current with latest production statement" in clj-geo
osm-pss-integration.job.qa to render source-geojson and new-trail using
clj-geo.visualization.map/geojson-style-extended-layer and to use div marker
displaying number. For each trail 10 markers should be drawn.

CLAUDE-2
Use GPX files for source geojson in "compare current with latest production 
statement" instead of http://localhost:7077/route/source/

CLAUDE-3
Modify "compare current with latest production" to use middle point of each 
segment inside trail ( MultiLineString ) as marker. Implement this only for
new trail and leave as it was for source.

CLAUDE-4
Create additional overlay in "compare current with latest production" which will
contain only changed parts of new trail compared with production trail. draw them
in orange. Precission should be on point level, meaning draw in orange line only
points that are changed.

CLAUDE-5
Provide html report in "compare current with latest production"
Generate index.html in staze-pss-rs-diff directory, containing similar output as
currently reported to console. Add link to <ref>.html diff file to open in new
tab and link to http://localhost:7077/route/edit/<id> where id is osm relation
id. Report ref, id, name and what changed.

CLAUDE-6
Add link to pss (website tag from relation), osm (https://osm.org/relation/<id>)
and history (http://localhost:7077/view/osm/history/relation/11313552) to open
in new tab as other.

CLAUDE-7
Modify "compare current with latest production" to display more than one index
for new trails when seqment is contained multiple times in route, Change only
for new trail.

CLAUDE-8
Modify "compare current with latest production" to report if both MODIFIED_GEOM
and MODIFIED_PROPERTIES occur.
