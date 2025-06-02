./mosaic.sh -s Monza -w 0
---
echo ""
echo "=== RESUMO DO SISTEMA COMPLETO ==="
echo "1. VEÍCULOS ATIVOS:"
ls logs/$LOG_DIR/apps/ | grep veh | wc -l

echo "2. RSUs ATIVAS:"
ls logs/$LOG_DIR/apps/ | grep rsu | wc -l

echo "3. SERVIDORES FOG:"
ls logs/$LOG_DIR/apps/ | grep server | wc -l
---
echo ""
echo "=== LOGS MAIS RECENTES DE CADA COMPONENTE ==="
echo "--- FOG SERVER ---"
find logs/$LOG_DIR/apps/ -name "FogServerApp.log" -exec tail -3 {} \;

echo "--- RSUs ---"
find logs/$LOG_DIR/apps/ -name "RsuApp.log" -exec tail -2 {} \;

echo "--- VEÍCULOS ---"
find logs/$LOG_DIR/apps/ -name "VehicleApp.log" -exec tail -1 {} \; | head -5
