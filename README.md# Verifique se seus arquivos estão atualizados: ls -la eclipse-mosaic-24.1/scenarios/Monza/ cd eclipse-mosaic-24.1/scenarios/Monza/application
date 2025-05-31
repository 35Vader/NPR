# Verifique se seus arquivos estão atualizados:
ls -la eclipse-mosaic-24.1/scenarios/Monza/
cd eclipse-mosaic-24.1/scenarios/Monza/application
javac -cp "/Users/alexandracandeias/Downloads/NPR-main/eclipse-mosaic-25.0/lib*" *.java

cd eclipse-mosaic-24.1
./mosaic.sh -s Monza -w 0

cd sumo
netedit -s monza.net.xml (para abrir o netedit e editar o mapa)
cd scenario-convert-24.1
./scenario-convert.sh --sumo2db -i ../eclipse-mosaic-24.1/scenarios/Monza/sumo/monza.net.xml -d ../ecLipse-mos
aic-24.1/scenarios/Monza/application/monza.db
./scenario-convert.sh --sumo2db -i ../eclipse-mosaic-24.1/scenarios/Monza/sumo/monza.rou.xml -d ../eclipse-mos
aic-24.1/scenarios/Monza/application/monza.db
cd mosaic
./mosaic.sh -s Monza -w 0
