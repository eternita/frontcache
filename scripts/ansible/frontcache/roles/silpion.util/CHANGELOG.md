# 2.0.4
Anja Siek (1):

* need default empty list to fix warning

Georg Hopp (11):

* Disable requiretty in SUDO if pipelining is requested.
* Fix indentation
* fix when condition
* Register custom var for pipelining as we overwrite ansible\_ssh\_pipelining for that task.
* Style cleanup
* Disable requiretty in SUDO if pipelining is requested.
* Fix indentation
* fix when condition
* Register custom var for pipelining as we overwrite ansible\_ssh\_pipelining for that task.
* Style cleanup
* add tag

Mark Kusch (3):

* Fix deprecation warning and integrate service management configuration vars
* Add service management configuration vars to local facts
* Add documentation for service configuration facts

# 2.0.3

Mark Kusch (1):

* PEBKAC

# 2.0.2

Mark Kusch (1):

* Fix deprecation warning for ansible-galaxy

# 2.0.1

Mark Kusch (1):

* HACK ansible-galaxy when recursing into dependencies

# 2.0.0

Alvaro Aleman (1):

* Avoid executing Install epel task when epel is already installed

Mark Kusch (17):

* Default to not using privilege escalation when local\_action
* Implement TODOs for configurable privilege escalation
* This role now requires Ansible version 2.0.0.2
* Use common tasks from silpion.lib role
* Update documentation for 2.0.0

# 1.1.0

Alvaro Aleman (1):

* added libsemanage-python to util\_package\_list\_selinux

Mark Kusch (1):

* Do not yum makecache all the time

# 1.0.0

Alvaro Aleman (4):

* TDD updated
* checkmode implemented
* Removed hard dependency on epel for RedHat, cleaned up epel installation
* README adjusted

Mark Kusch (15):

* Add EPEL configuration to local facts
* Remove obsolet util related variables
* Deploy local facts with json and add several facts
* Update documentation for local facts
* Allow to store role version as local fact
* Remove documentation for non-existing var
* Add assertion on EPEL configuration
* Rename local facts according to role variable name for util\_epel\_enable
* Use complex args coding style
* Do not uninstall EPEL package conditionally
* Add vagrant environment for CentOS 7
* Allow to configure facts for service management with Ansible
* Add apt facts in ansible\_os\_family == "Debian" only

# 0.4.0

Mark Kusch (19):

* Provide description to test playbook
* Prepare defaults to configure become framework
* Document privilege escalation configuration and bugs
* Cleanup vagrant environment
* Add boilerplate variables according to documentation
* Hardcode sudo for variable substitution bug reasons
* Do not require sudo: yes for local and remote persistence pathes
* Fix documentation for correct variable name
* Use a hidden assets directory per default
* Allow configuration of timeout= argument to get_url module
* Add wrapper to ensure persistency paradigm for down- and uploads
* s/sudo/become/g
* Ensure uploaded assets are usable for all users
* Allow autodetection of init system
* Cleanup OS specific vars
* Assert on valid/supported init system
* Re-read local facts only on facts update
* Fix upload directory access mode
* Reduce redundancy in OS data

# 0.3.0

Mark Kusch (1):

* Revert to version 0.1.0
  https://groups.google.com/forum/#!topic/ansible-devel/JlHq-SohYWo

# 0.2.0

Mark Kusch (2):

* Use new Ansible become subsystem
* Update documentation for the become subsystem

# 0.1.0

Mark Kusch (4):

* Remove empty handlers file from repository
* Allow installation of custom packages
* Do not install EPEL configuration if it should not get installed
* Smart configuration for local\_action tasks

# 0.0.1

* Initial commit


<!-- vim: set nofen ts=4 sw=4 et: -->
