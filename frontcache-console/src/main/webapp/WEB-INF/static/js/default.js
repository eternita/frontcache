function renderScreen() {
  if(window.pageYOffset > 25 || window.innerWidth < 992){
      $('.navbar-main').addClass('navbar-scrolling');
  } else {
      $('.navbar-main').removeClass('navbar-scrolling');
  }
}

window.onscroll = function() {
  renderScreen();
};

window.onresize = function() {
  renderScreen();
};


