// Обработчик запросов к движку (работает и в Worker, и в странице)
var pending=null;
function key(){var s='';for(var r=0;r<8;r++)for(var f=0;f<8;f++)s+=E.get(r*16+f)+',';return s}
function handle(q){
 try{
  E.setAdapt(!!q.adapt);
  if(pending){ // ищем, каким ходом соперник ответил, чтобы обновить «точность игрока»
    E.setFen(q.fen);var tk=key();
    E.setFen(pending.fen);E.play(pending.m);
    var lm=E.moves(),found=0;
    for(var i=0;i<lm.length;i++){E.play(lm[i]);var k=key();E.undo();if(k===tk){found=lm[i];break}}
    if(found)E.feedback(found);
    pending=null;
  }
  E.setFen(q.fen);
  var ov=E.over();
  if(ov)return{id:q.id,none:true,over:ov};
  var r=E.think(q.depth,q.ms,q.cun);
  if(!r)return{id:q.id,none:true,over:'Нет ходов'};
  pending={fen:q.fen,m:r.m};
  var f=r.m&127,t=(r.m>>7)&127,pr=(r.m>>14)&7;
  return{id:q.id,ff:f&7,fr:f>>4,tf:t&7,tr:t>>4,pr:pr,info:r.info};
 }catch(err){return{id:q.id,error:String(err)}}
}
