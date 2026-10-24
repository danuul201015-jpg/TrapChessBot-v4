importScripts('engine.js','handler.js');
onmessage=function(e){var r;try{r=handle(e.data)}catch(err){r={id:e.data.id,error:String(err)}}postMessage(r)};
