./mosaic.sh -s Monza -w 0
---
./demo_simulation.sh
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
