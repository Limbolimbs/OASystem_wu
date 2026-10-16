$(function(){
 "use strict";
 const message=document.getElementById("enrollmentMessage"),start=document.getElementById("enrollmentStart"),scan=document.getElementById("enrollmentScan"),done=document.getElementById("enrollmentDone"),qr=document.getElementById("enrollmentQr");let busy=false,expiry=null;
 const fragment=new URLSearchParams(location.hash.slice(1));if(fragment.has("token")){start.token.value=fragment.get("token");start.login.value=fragment.get("login")||"";history.replaceState(null,"",location.pathname);}
 function notice(text,error=false){message.textContent=text;message.classList.toggle("error",error);message.hidden=false;}
 function show(result){clearTimeout(expiry);start.hidden=true;const pending=result.state==="PENDING";scan.hidden=pending;done.hidden=!pending;if(!pending){qr.src=result.qr;expiry=setTimeout(()=>{qr.removeAttribute("src");scan.hidden=true;notice("QR登録の期限が切れました。最高管理者へ再招待を依頼してください。",true);},Math.max(0,Math.min(600000,Number(result.expiresInMilliseconds)||0)));}else qr.removeAttribute("src");}
 if(!fragment.has("token"))$.ajax({url:"/enrollment/status",dataType:"json",success:show,error:function(xhr){if(xhr.status!==401)notice(xhr.responseJSON?.message||"登録状態を取得できません。",true);}});
 $("#enrollmentStart,#enrollmentConfirm").on("submit",function(event){event.preventDefault();if(busy)return;const form=this;if(!form.checkValidity()){notice("入力項目を確認してください。",true);return;}if(form===start && form.password.value!==form.confirm.value){notice("パスワードが一致しません。",true);return;}
  busy=true;const data=$(form).serialize();form.querySelectorAll("button,input").forEach(e=>e.disabled=true);
  $.ajax({url:form===start?"/enrollment/start":"/enrollment/confirm",type:"POST",data,dataType:"json",success:function(result){if(form===start){show(result);start.reset();notice("本人の認証器でQRをスキャンしてください。");}else{show({state:"PENDING"});notice(result.message);form.reset();}},error:function(xhr){notice(xhr.responseJSON?.message||"結果を確認できません。再送信前に状態を確認してください。",true);},complete:function(){busy=false;form.querySelectorAll("button,input").forEach(e=>e.disabled=false);}});
 });
 document.addEventListener("visibilitychange",()=>{if(document.hidden)qr.hidden=true;else qr.hidden=false;});
});
