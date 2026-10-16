(() => {
 "use strict";
 $.ajaxPrefilter(function(options){
  const previous=options.beforeSend;
  options.beforeSend=function(xhr,settings){
   const url=new URL(settings.url,location.href);
   if(url.origin!==location.origin)return false;
   if(!/^(GET|HEAD|OPTIONS)$/i.test(settings.type)){
    const token=document.querySelector('meta[name="_csrf"]')?.content,name=document.querySelector('meta[name="_csrf_header"]')?.content;
    if(!token||!name){xhr.responseJSON={message:"画面の安全情報を取得できません。再表示してください。"};if(settings.error)xhr.fail(settings.error);if(settings.complete)xhr.always(()=>settings.complete(xhr,"canceled"));return false;}
    xhr.setRequestHeader(name,token);
   }
   xhr.setRequestHeader("X-Requested-With","XMLHttpRequest");
   return previous?previous.call(this,xhr,settings):undefined;
  };
 });
})();
