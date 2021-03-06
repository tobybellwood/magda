minikube config set memory 4096
minikube start
eval $(minikube docker-env)

sbt editsource:clean editsource:edit
kubectl create -f deploy/kubernetes/registry.yml

docker build -t localhost:5000/data61/magda-sbt-local:latest -f deploy/docker/sbt.local.dockerfile deploy/docker
docker push localhost:5000/data61/magda-sbt-local:latest

docker build -t localhost:5000/data61/elasticsearch-kubernetes:5.2.0 -f deploy/docker/elasticsearch-kubernetes.dockerfile deploy/docker
docker push localhost:5000/data61/elasticsearch-kubernetes:5.2.0

docker build -t localhost:5000/data61/registry-datastore:latest registry-datastore
docker push localhost:5000/data61/registry-datastore:latest

kubectl create -f target/kubernetes/local.yml
minikube service api