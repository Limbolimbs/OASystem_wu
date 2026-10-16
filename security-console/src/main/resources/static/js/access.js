$(function(){
 "use strict";
 let sending=false;
 $("#accessForm").on("submit",function(event){
  event.preventDefault();if(sending)return;
  const form=this,error=document.getElementById("accessError"),button=form.querySelector("button[type=submit]");
  error.hidden=true;
  if(!form.operator.value||!form.password.value||!/^\d{6}$/.test(form.otp.value)){error.textContent="管理ID・パスワード・6桁の認証コードを入力してください。";error.hidden=false;return;}
  sending=true;button.disabled=true;button.textContent="認証しています…";
  $.ajax({url:form.action,type:"POST",data:$(form).serialize(),dataType:"json",
   success:function(result){if(result.success&&result.redirectUrl==="/console")location.assign("/console");else{error.textContent="認証結果を確認できません。";error.hidden=false;}},
   error:function(xhr){const seconds=Number(xhr.getResponseHeader("Retry-After"));error.textContent=(xhr.responseJSON?.message||"認証サービスに接続できません。")+(seconds>0?` 再試行まで約${Math.ceil(seconds/60)}分です。`:"");error.hidden=false;form.otp.value="";},
   complete:function(){sending=false;button.disabled=false;button.textContent="認証して続行 →";}
  });
 });
});
