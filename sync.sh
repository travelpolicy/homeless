rsync -v -a ./build ./run.sh vridosh@192.168.1.143:/home/vridosh/homeless/
ssh root@192.168.1.143 'sudo systemctl restart homeless'