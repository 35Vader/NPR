./mosaic.sh -s Monza -w 0

LOG_DIR=$(ls -t logs/ | head -1)
echo "=== SISTEMA V2X DE SEGURANÇA RODOVIÁRIA ==="
echo "Veículos com aplicações ativas:"
ls logs/$LOG_DIR/apps/ | grep veh | wc -l
echo "RSUs processando dados:"
ls logs/$LOG_DIR/apps/ | grep rsu | wc -l

echo "=== TOTAL DE MENSAGENS V2X ENVIADAS ==="
grep -r "MENSAGENS ENVIADAS" logs/$LOG_DIR/apps/ | wc -l
echo "=== ALERTAS DE SEGURANÇA DETECTADOS ==="
grep -r "ALERTA CRÍTICO\|ENVIOU ALERTA" logs/$LOG_DIR/apps/ | wc -l
