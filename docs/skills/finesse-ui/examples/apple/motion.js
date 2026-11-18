/* finesse · register=brand · design-language=apple · apple-mode=editorial · A=neutral+blue · B=system-type · C=card-studies · D=GSAP-feedback · E=clean-surface */
(()=>{
 const g=window.gsap,F=window.Flip; if(!g)return;
 if(F)g.registerPlugin(F);if(window.Draggable)g.registerPlugin(Draggable);
 let reduce=matchMedia('(prefers-reduced-motion: reduce)').matches;
 const mm=g.matchMedia();mm.add({normal:'(prefers-reduced-motion: no-preference)',reduced:'(prefers-reduced-motion: reduce)'},ctx=>{reduce=ctx.conditions.reduced;if(reduce)g.globalTimeline.getChildren().forEach(a=>a.progress(1))});
 const duration=(n=.45)=>reduce?0:n;
 function flip(elements,change,done){const nodes=g.utils.toArray(elements);if(!F||reduce){change();done?.();return}F.killFlipsOf(nodes,true);const state=F.getState(nodes);change();return F.from(state,{duration:.55,ease:'power3.inOut',scale:true,nested:true,onComplete:done});}
 function bindDialog(dialog){
  let origin=null,animation=null,closing=false;
  const closeNative=dialog.close.bind(dialog);
  function finish(){closing=false;g.set(dialog,{clearProps:'transform,opacity,visibility'});closeNative();origin?.focus({preventScroll:true});document.documentElement.style.removeProperty('overflow')}
  dialog.close=()=>{if(!dialog.open||closing)return;closing=true;animation?.kill();const r=dialog.getBoundingClientRect(),o=origin?.getBoundingClientRect();const target=o&&o.width&&o.height?{x:o.x+o.width/2-r.x-r.width/2,y:o.y+o.height/2-r.y-r.height/2,scale:Math.min(.8,Math.max(.25,o.width/r.width))}:{y:18,scale:.96};animation=g.to(dialog,{...target,autoAlpha:0,duration:duration(.23),ease:'power2.in',onComplete:finish})};
  dialog.addEventListener('cancel',e=>{e.preventDefault();dialog.close()});
  dialog.addEventListener('click',e=>{if(e.target!==dialog)return;const r=dialog.getBoundingClientRect();if(e.clientX<r.left||e.clientX>r.right||e.clientY<r.top||e.clientY>r.bottom)dialog.close()});
  return {open(trigger){animation?.kill();closing=false;origin=trigger||document.activeElement;g.set(dialog,{clearProps:'transform,opacity,visibility'});if(!dialog.open)dialog.showModal();document.documentElement.style.overflow='hidden';const r=dialog.getBoundingClientRect(),o=origin?.getBoundingClientRect();const from=o?.width?{x:o.x+o.width/2-r.x-r.width/2,y:o.y+o.height/2-r.y-r.height/2,scale:Math.min(.85,Math.max(.35,o.width/r.width))}:{y:20,scale:.95};animation=g.timeline({defaults:{ease:'power3.out'}}).fromTo(dialog,{...from,autoAlpha:0},{x:0,y:0,scale:1,autoAlpha:1,duration:duration(.42),clearProps:'transform,opacity,visibility'});},close:()=>dialog.close()};
 }
 function feedback(root=document){root.addEventListener('pointerdown',e=>{const b=e.target.closest('button');if(!b||b.disabled||b.matches('.pass,.film-card,.lens'))return;g.to(b,{scale:.97,duration:duration(.1),overwrite:'auto'})});const release=()=>root.querySelectorAll('button').forEach(b=>{if(b.matches('.pass,.film-card,.lens'))return;if(g.getProperty(b,'scaleX')!==1)g.to(b,{scale:1,duration:duration(.25),overwrite:'auto',clearProps:'transform'})});root.addEventListener('pointerup',release);root.addEventListener('pointercancel',release)}
 window.AppleMotion={g,flip,duration,bindDialog,feedback,get reduced(){return reduce}};
 addEventListener('pagehide',()=>{g.globalTimeline.clear();mm.revert()},{once:true});
})();
