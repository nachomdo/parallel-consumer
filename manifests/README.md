## soak testing environment 

### goal 

To provide a quick Kubernetes environment with all the necessary dependencies to troubleshoot and conduct all sorts of performance testing to the parallel-consumer (PC). 

### what's in the box? 

The following components will be installed: 

* Prometheus with scrapers targetting PC metrics endpoints
* Grafana and Kafka consumer dashboard
* Chaos Mesh with example rules to trigger rebalances in PC
* A cloud native incarnation of Trogdor (Tarasque) for load generation  
* 

### quick setup 

1. Spin up a new GKE cluster

```bash
export CLUSTER_NAME=your-gke-cluster
gcloud container clusters create $CLUSTER_NAME --machine-type e2-standard-8  --num-nodes 3 --spot
```

2. Integrate with Confluent Cloud

```bash
kubectl create ns confluent
kubectl create -n confluent secret generic pc-secrets --from-literal=username=$YOUR_API_KEY --from-literal=password=$YOUR_API_SECRET
```

3. Deploy 

```bash
kustomize build overlays/fix3 --enable-helm | kubectl apply --server-side=true --force-conflicts -f -
```