<?php
header('X-frontcache.component.maxage: 3m');
?>
  <div class="row">
    
    <div class="col-sm-9 demo-header" style="padding-top: 30px; padding-left: 200px; ">
    
        <b>Page Header</b> 
        <br/>User/Bot: TTL = 3 min (from cache)
    
    </div>
    
    <fc:include url="/user-profile.php"/>
        
  </div>
